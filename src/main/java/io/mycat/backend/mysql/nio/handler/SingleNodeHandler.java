/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.backend.mysql.nio.handler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.common.base.Strings;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.backend.mysql.LoadDataUtil;
import io.mycat.config.ErrorCode;
import io.mycat.config.MycatConfig;
import io.mycat.config.model.SchemaConfig;
import io.mycat.net.mysql.BinaryRowDataPacket;
import io.mycat.net.mysql.ErrorPacket;
import io.mycat.net.mysql.FieldPacket;
import io.mycat.net.mysql.OkPacket;
import io.mycat.net.mysql.RowDataPacket;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.server.NonBlockingSession;
import io.mycat.server.ServerConnection;
import io.mycat.server.parser.ServerParse;
import io.mycat.server.parser.ServerParseShow;
import io.mycat.server.response.ShowFullTables;
import io.mycat.server.response.ShowTables;
import io.mycat.statistic.stat.QueryResult;
import io.mycat.statistic.stat.QueryResultDispatcher;
import io.mycat.util.ResultSetUtil;
import io.mycat.util.StringUtil;

import org.slf4j.Logger; import org.slf4j.LoggerFactory;
/**
 * @author mycat
 */
public class SingleNodeHandler implements ResponseHandler, Terminatable, LoadDataResponseHandler {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SingleNodeHandler.class);
	
	private final RouteResultsetNode node;
	private final RouteResultset rrs;
	private final NonBlockingSession session;
	
	// only one thread access at one time no need lock
	private volatile byte packetId;
	private volatile ByteBuffer buffer;
	private volatile boolean isRunning;
	private Runnable terminateCallBack;
	private long startTime;
	private long netInBytes;
	private long netOutBytes;
	private long selectRows;
	private long affectedRows;
	
	private boolean prepared;
	private int fieldCount;
	private List<FieldPacket> fieldPackets = new ArrayList<FieldPacket>();

    private volatile boolean isDefaultNodeShowTable;
    private volatile boolean isDefaultNodeShowFullTable;
    private  Set<String> shardingTablesSet;
	private byte[] header = null;
	private List<byte[]> fields = null;
	public SingleNodeHandler(RouteResultset rrs, NonBlockingSession session) {
		this.rrs = rrs;
		this.node = rrs.getNodes()[0];
		
		if (node == null) {
			throw new IllegalArgumentException("routeNode is null!");
		}
		
		if (session == null) {
			throw new IllegalArgumentException("session is null!");
		}
		
		this.session = session;
		ServerConnection source = session.getSource();
		String schema = source.getSchema();
		if (schema != null && ServerParse.SHOW == rrs.getSqlType()) {
			SchemaConfig schemaConfig = MycatServer.getInstance().getConfig().getSchemas().get(schema);
			int type = ServerParseShow.tableCheck(rrs.getStatement(), 0);
			isDefaultNodeShowTable = (ServerParseShow.TABLES == type && !Strings.isNullOrEmpty(schemaConfig.getDataNode()));
			isDefaultNodeShowFullTable = (ServerParseShow.FULLTABLES == type && !Strings.isNullOrEmpty(schemaConfig.getDataNode()));
			if (isDefaultNodeShowTable) {
				shardingTablesSet = ShowTables.getTableSet(source, rrs.getStatement());
				
			} else if (isDefaultNodeShowFullTable) {
				shardingTablesSet = ShowFullTables.getTableSet(source, rrs.getStatement());
			}
		}
        
		if ( rrs != null && rrs.getStatement() != null) {
			netInBytes += rrs.getStatement().getBytes().length;
		}
        
	}

	@Override
	public void terminate(Runnable callback) {
		boolean zeroReached = false;

		if (isRunning) {
			terminateCallBack = callback;
		} else {
			zeroReached = true;
		}

		if (zeroReached) {
			callback.run();
		}
	}

	private void endRunning() {
		Runnable callback = null;
		if (isRunning) {
			isRunning = false;
			callback = terminateCallBack;
			terminateCallBack = null;
		}

		if (callback != null) {
			callback.run();
		}
	}

	private void recycleResources() {

		ByteBuffer buf = buffer;
		if (buf != null) {
			session.getSource().recycle(buffer);
			buffer = null;
		}
	}

	public void execute() throws Exception {
		startTime=System.currentTimeMillis();
		ServerConnection sc = session.getSource();
		this.isRunning = true;
		this.packetId = 0;
		final BackendConnection conn = session.getTarget(node);
		LOGGER.debug("rrs.getRunOnSlave() " + rrs.getRunOnSlave());
		node.setRunOnSlave(rrs.getRunOnSlave());	// ?????? master/slave??????
		LOGGER.debug("node.getRunOnSlave() " + node.getRunOnSlave());
		 
		if (session.tryExistsCon(conn, node)) {
			_execute(conn);
		} else {
			// create new connection

			MycatConfig conf = MycatServer.getInstance().getConfig();
						
			LOGGER.debug("node.getRunOnSlave() " + node.getRunOnSlave());
			node.setRunOnSlave(rrs.getRunOnSlave());	// ?????? master/slave??????
			LOGGER.debug("node.getRunOnSlave() " + node.getRunOnSlave());
			 		
			PhysicalDBNode dn = conf.getDataNodes().get(node.getName());
			dn.getConnection(dn.getDatabase(), sc.isAutocommit(), node, this, node);
		}

	}

	@Override
	public void connectionAcquired(final BackendConnection conn) {
		session.bindConnection(node, conn);
		_execute(conn);

	}

	private void _execute(BackendConnection conn) {
		if (session.closed()) {
			endRunning();
			session.clearResources(true);
			return;
		}
		conn.setResponseHandler(this);
		try {
			conn.execute(node, session.getSource(), session.getSource()
					.isAutocommit());
		} catch (Exception e1) {
			executeException(conn, e1);
			return;
		}
	}

	private void executeException(BackendConnection c, Exception e) {
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ERR_FOUND_EXCEPION;
		err.message = StringUtil.encode(e.toString(), session.getSource().getCharset());

		this.backConnectionErr(err, c);
	}

	@Override
	public void connectionError(Throwable e, BackendConnection conn) {

		endRunning();
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ER_NEW_ABORTING_CONNECTION;
		err.message = StringUtil.encode(e.getMessage(), session.getSource().getCharset());
		
		ServerConnection source = session.getSource();
		source.write(err.write(allocBuffer(), source, true));
	}

	@Override
	public void errorResponse(byte[] data, BackendConnection conn) {
		ErrorPacket err = new ErrorPacket();
		err.read(data);
		err.packetId = ++packetId;
		backConnectionErr(err, conn);
	}

	private void backConnectionErr(ErrorPacket errPkg, BackendConnection conn) {
		endRunning();
		
		ServerConnection source = session.getSource();
		String errUser = source.getUser();
		String errHost = source.getHost();
		int errPort = source.getLocalPort();
		
		String errmgs = " errno:" + errPkg.errno + " " + new String(errPkg.message);
		LOGGER.warn("execute  sql err :" + errmgs + " con:" + conn 
				+ " frontend host:" + errHost + "/" + errPort + "/" + errUser);
		
		session.releaseConnectionIfSafe(conn, LOGGER.isDebugEnabled(), false);
		
		source.setTxInterrupt(errmgs);
		
		/**
		 * TODO: ???????????????BUG
		 * 
		 * BUG?????????
		 * 1???MysqlClient:  SELECT 9223372036854775807 + 1;
		 * 2???MyCatServer:  ERROR 1690 (22003): BIGINT value is out of range in '(9223372036854775807 + 1)'
		 * 3???MysqlClient: ERROR 2013 (HY000): Lost connection to MySQL server during query
		 * 
		 * Fixed???
		 * 1???MysqlClient:  SELECT 9223372036854775807 + 1;
		 * 2???MyCatServer:  ERROR 1690 (22003): BIGINT value is out of range in '(9223372036854775807 + 1)'
		 * 3???MysqlClient: ERROR 1690 (22003): BIGINT value is out of range in '(9223372036854775807 + 1)'
		 * 
		 */		
		// ?????? pakcetId != 1 ??????????????? 
		errPkg.packetId = 1;		
		errPkg.write(source);
		
		recycleResources();
	}


	/**
	 * insert/update/delete
	 * 
	 * okResponse()?????????data???????????????????????????OKPacket????????????ok.write(source)???????????????????????????FrontendConnection??????????????????writeQueue??????
	 * ????????????????????????????????????NIOSocketWR?????????????????????ByteBuffer????????????
	 */
	@Override
	public void okResponse(byte[] data, BackendConnection conn) {      
		//
		this.netOutBytes += data.length;
		
		boolean executeResponse = conn.syncAndExcute();		
		if (executeResponse) {
			ServerConnection source = session.getSource();
			OkPacket ok = new OkPacket();
			ok.read(data);
            boolean isCanClose2Client =(!rrs.isCallStatement()) ||(rrs.isCallStatement() &&!rrs.getProcedure().isResultSimpleValue());
			if (rrs.isLoadData()) {				
				byte lastPackId = source.getLoadDataInfileHandler().getLastPackId();
				ok.packetId = ++lastPackId;// OK_PACKET
				source.getLoadDataInfileHandler().clear();
				
			} else if (isCanClose2Client) {
				ok.packetId = ++packetId;// OK_PACKET
			}


			if (isCanClose2Client) {
				session.releaseConnectionIfSafe(conn, LOGGER.isDebugEnabled(), false);
				endRunning();
			}
			ok.serverStatus = source.isAutocommit() ? 2 : 1;
			recycleResources();

			if (isCanClose2Client) {
				source.setLastInsertId(ok.insertId);
				ok.write(source);
			}
            
			this.affectedRows = ok.affectedRows;
			
			source.setExecuteSql(null);
			// add by lian
			// ??????sql???????????????????????????0
			QueryResult queryResult = new QueryResult(session.getSource().getUser(), 
					rrs.getSqlType(), rrs.getStatement(), affectedRows, netInBytes, netOutBytes, startTime, System.currentTimeMillis(),0);
			QueryResultDispatcher.dispatchQuery( queryResult );
		}
	}

	
	/**
	 * select 
	 * 
	 * ????????????????????????????????????EOF????????????????????????????????????source.write(buffer)????????????????????????????????????????????????????????????NIOSocketWR?????????????????????
	 */
	@Override
	public void rowEofResponse(byte[] eof, BackendConnection conn) {
		
		this.netOutBytes += eof.length;
		
		ServerConnection source = session.getSource();
		conn.recordSql(source.getHost(), source.getSchema(), node.getStatement());
        // ????????????????????????????????????????????????????????????
		if (!rrs.isCallStatement()||(rrs.isCallStatement()&&rrs.getProcedure().isResultSimpleValue())) 
		{
			session.releaseConnectionIfSafe(conn, LOGGER.isDebugEnabled(), false);
			endRunning();
		}

		eof[3] = ++packetId;
		buffer = source.writeToBuffer(eof, allocBuffer());
		int resultSize = source.getWriteQueue().size()*MycatServer.getInstance().getConfig().getSystem().getBufferPoolPageSize();
		resultSize=resultSize+buffer.position();
		MiddlerResultHandler middlerResultHandler = session.getMiddlerResultHandler();

		if(middlerResultHandler !=null ){
			middlerResultHandler.secondEexcute(); 
		} else{
			source.write(buffer);
		}
		source.setExecuteSql(null);
		//TODO: add by zhuam
		//??????????????????
		QueryResult queryResult = new QueryResult(session.getSource().getUser(), 
				rrs.getSqlType(), rrs.getStatement(), affectedRows, netInBytes, netOutBytes, startTime, System.currentTimeMillis(),resultSize);
		QueryResultDispatcher.dispatchQuery( queryResult );
		
	}

	/**
	 * lazy create ByteBuffer only when needed
	 * 
	 * @return
	 */
	private ByteBuffer allocBuffer() {
		if (buffer == null) {
			buffer = session.getSource().allocate();
		}
		return buffer;
	}

	/**
	 * select
	 * 
	 * ??????????????????????????????header??????????????????????????????????????????
	 */	
	@Override
	public void fieldEofResponse(byte[] header, List<byte[]> fields,
			byte[] eof, BackendConnection conn) {
		this.header = header;
		this.fields = fields;
		MiddlerResultHandler middlerResultHandler = session.getMiddlerResultHandler();
        if(null !=middlerResultHandler ){
			return;
		}
		this.netOutBytes += header.length;
		for (int i = 0, len = fields.size(); i < len; ++i) {
			byte[] field = fields.get(i);
			this.netOutBytes += field.length;
		}

		header[3] = ++packetId;
		ServerConnection source = session.getSource();
		buffer = source.writeToBuffer(header, allocBuffer());
		for (int i = 0, len = fields.size(); i < len; ++i) {
			byte[] field = fields.get(i);
			field[3] = ++packetId;
			
			 // ??????field??????
 			FieldPacket fieldPk = new FieldPacket();
 			fieldPk.read(field);
 			fieldPackets.add(fieldPk);
			
			buffer = source.writeToBuffer(field, buffer);
		}
		
		fieldCount = fieldPackets.size();
		
		eof[3] = ++packetId;
		buffer = source.writeToBuffer(eof, buffer);

		if (isDefaultNodeShowTable) {
			
			for (String name : shardingTablesSet) {
				RowDataPacket row = new RowDataPacket(1);
				row.add(StringUtil.encode(name.toLowerCase(), source.getCharset()));
				row.packetId = ++packetId;
				buffer = row.write(buffer, source, true);
			}
			
		} else if (isDefaultNodeShowFullTable) {
			
			for (String name : shardingTablesSet) {
				RowDataPacket row = new RowDataPacket(1);
				row.add(StringUtil.encode(name.toLowerCase(), source.getCharset()));
				row.add(StringUtil.encode("BASE TABLE", source.getCharset()));
				row.packetId = ++packetId;
				buffer = row.write(buffer, source, true);
			}
		}
	}

	/**
	 * select 
	 * 
	 * ?????????????????????????????????????????????????????????
	 */
	@Override
	public void rowResponse(byte[] row, BackendConnection conn) {
		
		this.netOutBytes += row.length;
		this.selectRows++;
		
		if (isDefaultNodeShowTable || isDefaultNodeShowFullTable) {
			RowDataPacket rowDataPacket = new RowDataPacket(1);
			rowDataPacket.read(row);
			String table = StringUtil.decode(rowDataPacket.fieldValues.get(0), session.getSource().getCharset());
			if (shardingTablesSet.contains(table.toUpperCase())) {
				return;
			}
		}
		row[3] = ++packetId;
		
		if ( prepared ) {			
			RowDataPacket rowDataPk = new RowDataPacket(fieldCount);
			rowDataPk.read(row);			
			BinaryRowDataPacket binRowDataPk = new BinaryRowDataPacket();
			binRowDataPk.read(fieldPackets, rowDataPk);
			binRowDataPk.packetId = rowDataPk.packetId;
//			binRowDataPk.write(session.getSource());
			/*
			 * [fix bug] : ??????????????????????????????????????????,
			 * ?????????fieldEofResponse()???????????????buffer????????????,
			 * ??????????????????????????????????????????buffer(??????buffer???????????????),????????????buffer??????
			 */
			buffer = binRowDataPk.write(buffer, session.getSource(), true);
		} else {

			MiddlerResultHandler middlerResultHandler = session.getMiddlerResultHandler();
	        if(null ==middlerResultHandler ){
	        	 buffer = session.getSource().writeToBuffer(row, allocBuffer());
			}else{
		        if(middlerResultHandler instanceof MiddlerQueryResultHandler){
		        	byte[] rv = ResultSetUtil.getColumnVal(row, fields, 0);
					 	 String rowValue =  rv==null?"":new String(rv);
						 middlerResultHandler.add(rowValue);	
 				 }
			}
		 
		}

	}

	@Override
	public void writeQueueAvailable() {

	}

	@Override
	public void connectionClose(BackendConnection conn, String reason) {
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ER_ERROR_ON_CLOSE;
		err.message = StringUtil.encode(reason, session.getSource()
				.getCharset());
		this.backConnectionErr(err, conn);

	}

	public void clearResources() {

	}

	@Override
	public void requestDataResponse(byte[] data, BackendConnection conn) {
		LoadDataUtil.requestFileDataResponse(data, conn);
	}
	
	public boolean isPrepared() {
		return prepared;
	}

	public void setPrepared(boolean prepared) {
		this.prepared = prepared;
	}

	@Override
	public String toString() {
		return "SingleNodeHandler [node=" + node + ", packetId=" + packetId + "]";
	}

}