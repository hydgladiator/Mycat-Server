package io.mycat.backend.postgresql;

import io.mycat.MycatServer;
import io.mycat.backend.mysql.nio.handler.ResponseHandler;
import io.mycat.backend.postgresql.PostgreSQLBackendConnection.BackendConnectionState;
import io.mycat.backend.postgresql.packet.AuthenticationPacket;
import io.mycat.backend.postgresql.packet.AuthenticationPacket.AuthType;
import io.mycat.backend.postgresql.packet.BackendKeyData;
import io.mycat.backend.postgresql.packet.CommandComplete;
import io.mycat.backend.postgresql.packet.CopyInResponse;
import io.mycat.backend.postgresql.packet.CopyOutResponse;
import io.mycat.backend.postgresql.packet.DataRow;
import io.mycat.backend.postgresql.packet.EmptyQueryResponse;
import io.mycat.backend.postgresql.packet.ErrorResponse;
import io.mycat.backend.postgresql.packet.NoticeResponse;
import io.mycat.backend.postgresql.packet.NotificationResponse;
import io.mycat.backend.postgresql.packet.ParameterStatus;
import io.mycat.backend.postgresql.packet.PasswordMessage;
import io.mycat.backend.postgresql.packet.PostgreSQLPacket;
import io.mycat.backend.postgresql.packet.ReadyForQuery;
import io.mycat.backend.postgresql.packet.ReadyForQuery.TransactionState;
import io.mycat.backend.postgresql.packet.RowDescription;
import io.mycat.backend.postgresql.utils.PacketUtils;
import io.mycat.backend.postgresql.utils.PgPacketApaterUtils;
import io.mycat.buffer.BufferArray;
import io.mycat.config.ErrorCode;
import io.mycat.net.handler.BackendAsyncHandler;
import io.mycat.net.mysql.EOFPacket;
import io.mycat.net.mysql.ErrorPacket;
import io.mycat.net.mysql.FieldPacket;
import io.mycat.net.mysql.OkPacket;
import io.mycat.net.mysql.ResultSetHeaderPacket;
import io.mycat.net.mysql.RowDataPacket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

public class PostgreSQLBackendConnectionHandler extends BackendAsyncHandler {
	static class SelectResponse {
		private List<DataRow> dataRows = new ArrayList<>();

		private RowDescription description;

		public SelectResponse(RowDescription description) {
			this.description = description;
		}

		public void addDataRow(DataRow packet) {
			this.dataRows.add(packet);
		}

		public List<DataRow> getDataRows() {
			return dataRows;
		}

		public RowDescription getDescription() {
			return description;
		}

		public void setDataRows(List<DataRow> dataRows) {
			this.dataRows = dataRows;
		}

	}

	private static final Logger LOGGER = LoggerFactory
			.getLogger(PostgreSQLBackendConnection.class);
	private static final int RESULT_STATUS_INIT = 0;

	private byte packetId = 1;

	/*****
	 * ????????????????????????????????????
	 */
	private final PostgreSQLBackendConnection source;
	
	/**
	 * ????????????
	 */
	private volatile SelectResponse response = null;
	
	/**
	 * ????????????
	 */
	private int resultStatus;

	public PostgreSQLBackendConnectionHandler(PostgreSQLBackendConnection source) {
		this.source = source;
	}

	/***
	 * ??????????????????
	 * 
	 * @param con
	 * @param buf
	 * @param start
	 * @param readedLength
	 */
	private void doConnecting(PostgreSQLBackendConnection con, ByteBuffer buf,
			int start, int readedLength) {
		try {
			List<PostgreSQLPacket> packets = PacketUtils.parsePacket(buf, 0,
					readedLength);
			LOGGER.debug(JSON.toJSONString(packets));
			if (!packets.isEmpty()
					&& packets.get(0) instanceof AuthenticationPacket) {
				// pg????????????
					AuthenticationPacket packet = (AuthenticationPacket) packets
							.get(0);
					AuthType aut = packet.getAuthType();
					if (aut != AuthType.Ok) {
						PasswordMessage pak = new PasswordMessage(
								con.getUser(), con.getPassword(), aut,
								((AuthenticationPacket) packet).getSalt());
						
						ByteBuffer buffer = con.allocate(); //allocate(pak.getLength() + 1);
						pak.write(buffer);
						
						con.write(buffer);
					} else {// ???????????????....

						for (int i = 1; i < packets.size(); i++) {
							PostgreSQLPacket _p = packets.get(i);
							if (_p instanceof BackendKeyData) {
								con.setServerSecretKey(((BackendKeyData) _p)
										.getSecretKey());
							}
						}
						LOGGER.debug("SUCCESS Connected TO PostgreSQL , con id is {}",con.getId());
						con.setState(BackendConnectionState.connected);
						con.getResponseHandler().connectionAcquired(con);// ????????????????????????

					}


			}

		} catch (IOException e) {
			LOGGER.error("error",e);
		}
	}

	/***
	 * ??????????????????
	 * 
	 * @param con
	 * @param buf
	 * @param start
	 * @param readedLength
	 */
	private void doHandleBusinessMsg(PostgreSQLBackendConnection con,
			ByteBuffer buf, int start, int readedLength) {
		try {
			List<PostgreSQLPacket> packets = PacketUtils.parsePacket(buf, 0,
					readedLength);
			if (packets == null || packets.isEmpty()) {
				return ;
				//throw new RuntimeException("?????????????????????");
			}
			
			for (PostgreSQLPacket packet : packets) {
				if (packet instanceof ErrorResponse) {
					doProcessErrorResponse(con, (ErrorResponse) packet);
				} else if (packet instanceof RowDescription) {
					response = new SelectResponse((RowDescription) packet);
				} else if (packet instanceof DataRow) {
					response.addDataRow((DataRow) packet);
				} else if (packet instanceof ParameterStatus) {
					doProcessParameterStatus(con, (ParameterStatus) packet);
				} else if (packet instanceof CommandComplete) {
					doProcessCommandComplete(con, (CommandComplete) packet,
							response);
				} else if (packet instanceof NoticeResponse) {
					doProcessNoticeResponse(con, (NoticeResponse) packet);
				} else if (packet instanceof ReadyForQuery) {
					doProcessReadyForQuery(con, (ReadyForQuery) packet);
				} else if (packet instanceof NotificationResponse) {
					doProcessNotificationResponse(con,
							(NotificationResponse) packet);
				} else if (packet instanceof CopyInResponse) {
					doProcessCopyInResponse(con, (CopyInResponse) packet);
				} else if (packet instanceof CopyOutResponse) {
					doProcessCopyOutResponse(con, (CopyOutResponse) packet);
				} else if (packet instanceof EmptyQueryResponse) {
					doProcessEmptyQueryResponse(con,
							(EmptyQueryResponse) packet);
				}
			}
		} catch (Exception e) {
			LOGGER.error("??????????????????", e);
			ErrorPacket err = new ErrorPacket();
			err.packetId = ++packetId;
			err.message = ("???????????????????????????!" + e.getMessage()).getBytes();
			err.errno = ErrorCode.ERR_NOT_SUPPORTED;
			ResponseHandler respHand = con.getResponseHandler();
			if (respHand != null) {
				respHand.errorResponse(err.writeToBytes(), con);
			} else {
			 LOGGER.error("{},respHand ??????",this);
			}
		}
	}


	/***************
	 *  ???????????????????????? ,??????????????????????????? CommandComplete ?????????
	 * @param con PostgreSQL ????????????
	 * @param response
	 * @param commandComplete
     */
	private void doProcessBusinessQuery(PostgreSQLBackendConnection con,
			SelectResponse response, CommandComplete commandComplete) {
		RowDescription rowHd = response.getDescription();
		List<FieldPacket> fieldPks = PgPacketApaterUtils
				.rowDescConvertFieldPacket(rowHd);
		List<RowDataPacket> rowDatas = new ArrayList<>();
		for (DataRow dataRow : response.getDataRows()) {
			rowDatas.add(PgPacketApaterUtils
					.rowDataConvertRowDataPacket(dataRow));
		}

		BufferArray bufferArray = MycatServer.getInstance().getBufferPool()
				.allocateArray();
		ResultSetHeaderPacket headerPkg = new ResultSetHeaderPacket();
		headerPkg.fieldCount = fieldPks.size();
		headerPkg.packetId = ++packetId;
		headerPkg.write(bufferArray);

		byte[] header = bufferArray.writeToByteArrayAndRecycle();

		List<byte[]> fields = new ArrayList<byte[]>(fieldPks.size());
		Iterator<FieldPacket> itor = fieldPks.iterator();
		while (itor.hasNext()) {
			bufferArray = MycatServer.getInstance().getBufferPool()
					.allocateArray();
			FieldPacket curField = itor.next();
			curField.packetId = ++packetId;
			curField.write(bufferArray);
			byte[] field = bufferArray.writeToByteArrayAndRecycle();
			fields.add(field);
			itor.remove();
		}

		bufferArray = MycatServer.getInstance().getBufferPool().allocateArray();
		EOFPacket eofPckg = new EOFPacket();
		eofPckg.packetId = ++packetId;
		eofPckg.write(bufferArray);
		byte[] eof = bufferArray.writeToByteArrayAndRecycle();
		if (con.getResponseHandler() != null) {
			con.getResponseHandler().fieldEofResponse(header, fields, eof, con);
		} else {
			LOGGER.error("??????????????????");
		}
		// output row
		for (RowDataPacket curRow : rowDatas) {
			bufferArray = MycatServer.getInstance().getBufferPool()
					.allocateArray();
			curRow.packetId = ++packetId;
			curRow.write(bufferArray);
			byte[] row = bufferArray.writeToByteArrayAndRecycle();
			con.getResponseHandler().rowResponse(row, con);
		}

		// end row
		bufferArray = MycatServer.getInstance().getBufferPool().allocateArray();
		eofPckg = new EOFPacket();
		eofPckg.packetId = ++packetId;
		eofPckg.write(bufferArray);
		eof = bufferArray.writeToByteArrayAndRecycle();
		if (con.getResponseHandler() != null) {
			con.getResponseHandler().rowEofResponse(eof, con);
		} else {
			LOGGER.error("??????????????????");
		}
	}

	private void doProcessCommandComplete(PostgreSQLBackendConnection con,
			CommandComplete commandComplete, SelectResponse response) {
		if (commandComplete.isSelectComplete()) {
			if (response == null) {
				throw new RuntimeException(
						"the select proess err ,the SelectResponse is empty");
			}
			doProcessBusinessQuery(con, response, commandComplete);
		} else {
			OkPacket okPck = new OkPacket();
			
			okPck.affectedRows =commandComplete.getAffectedRows();
			okPck.insertId =commandComplete.getInsertId();
			okPck.packetId = ++packetId;
			okPck.message = commandComplete.getCommandResponse().getBytes();
			con.getResponseHandler().okResponse(okPck.writeToBytes(), con);
		}
	}

	private void doProcessCopyInResponse(PostgreSQLBackendConnection con,
			CopyInResponse packet) {
		// TODO(???????????????????????????)
	}

	private void doProcessCopyOutResponse(PostgreSQLBackendConnection con,
			CopyOutResponse packet) {
		// TODO(???????????????????????????)
	}

	private void doProcessEmptyQueryResponse(PostgreSQLBackendConnection con,
			EmptyQueryResponse packet) {
		// TODO(??????????????????sql)
	}

	/***
	 * ???????????????????????????
	 * 
	 * @param con
	 * @param errorResponse
	 */
	private void doProcessErrorResponse(PostgreSQLBackendConnection con,
			ErrorResponse errorResponse) {
		LOGGER.debug("???????????????!");
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.message = errorResponse.getErrMsg().trim().replaceAll("\0", " ")
				.getBytes();
		err.errno = ErrorCode.ER_UNKNOWN_ERROR;
		con.getResponseHandler().errorResponse(err.writeToBytes(), con);

	}

	/******
	 * ?????????????????????????????????
	 * 
	 * @param con
	 * @param noticeResponse
	 */
	private void doProcessNoticeResponse(PostgreSQLBackendConnection con,
			NoticeResponse noticeResponse) {
		// TODO (??????????????????)
	}

	private void doProcessNotificationResponse(PostgreSQLBackendConnection con,
			NotificationResponse notificationResponse) {
		// TODO(????????????????????????)
	}

	private void doProcessParameterStatus(PostgreSQLBackendConnection con,
			ParameterStatus parameterStatus) {
		// TODO(??????????????????)
	}


	/****
	 * PostgreSQL ???????????????????????????????????????????????????
	 * @param con
	 * @param readyForQuery
     */
	private void doProcessReadyForQuery(PostgreSQLBackendConnection con,
			ReadyForQuery readyForQuery) {
		if (con.isInTransaction() != (readyForQuery.getState() == TransactionState.IN)) {// ?????????????????????????????????
			con.setInTransaction((readyForQuery.getState() == TransactionState.IN));
		}
	}

	@Override
	public void handle(byte[] data) {
		offerData(data, source.getProcessor().getExecutor());
	}

	/*
	 * ???????????? ???????????????????????????
	 * 
	 * 
	 * 
	 * @see io.mycat.net.handler.BackendAsyncHandler#handleData(byte[])
	 */
	@Override
	protected void handleData(byte[] data) {
		ByteBuffer theBuf = null;
		try {
			theBuf = source.allocate();
			theBuf.put(data);
			switch (source.getState()) {
			case connecting: {
				doConnecting(source, theBuf, 0, data.length);
				return;
			}
			case connected: {
				try {
					doHandleBusinessMsg(source, theBuf , 0,
							data.length);
				} catch (Exception e) {
					LOGGER.warn("caught err of con " + source, e);
				}
				return;
			}

			default:
				LOGGER.warn("not handled connecton state  err "
						+ source.getState() + " for con " + source);
				break;

			}
		} catch (Exception e) {
			LOGGER.error("?????????????????????",e);
		}finally{
			if(theBuf!=null){
				source.recycle(theBuf);
			}
		}
	}

	@Override
	protected void offerDataError() {
		resultStatus = RESULT_STATUS_INIT;
		throw new RuntimeException("offer data error!");
	}

}
