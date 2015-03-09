package tracker;

import mysql.dbsync.DirectLogFetcherChannel;
import mysql.dbsync.LogContext;
import mysql.dbsync.LogDecoder;
import mysql.dbsync.LogEvent;
import mysql.driver.MysqlConnector;
import mysql.driver.MysqlQueryExecutor;
import mysql.driver.MysqlUpdateExecutor;
import mysql.driver.packets.HeaderPacket;
import mysql.driver.packets.client.BinlogDumpCommandPacket;
import mysql.driver.packets.server.ResultSetPacket;
import mysql.driver.utils.PacketManager;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.protobuf.CanalEntry;
import tracker.common.TableMetaCache;
import tracker.parser.LogEventConvert;
import tracker.position.EntryPosition;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Created by hp on 15-3-9.
 */
public class SimpleMysqlTracker {

    //static
    private static final String addr = "127.0.0.1";
    private static final int port = 3306;
    private static final String username = "canal";
    private static final String password = "canal";
    private static final long slaveId = 9876;


    private Logger logger = LoggerFactory.getLogger(SimpleMysqlTracker.class);
    private MysqlConnector connector;
    private MysqlConnector connectorTable;
    private MysqlQueryExecutor queryExecutor;
    private MysqlUpdateExecutor updateExecutor;
    private EntryPosition startPosition;
    private TableMetaCache tableMetaCache;
    private LogEventConvert eventParser;
    private DirectLogFetcherChannel fetcher;
    private LogDecoder decoder;
    private LogContext context;


    private void preDump() throws Exception {
        logger.info("prepare dump mysql......");
        connector = new MysqlConnector(new InetSocketAddress(addr, port), username, password);
        connectorTable = new MysqlConnector(new InetSocketAddress(addr, port), username, password);
        connector.connect();
        connectorTable.connect();
        queryExecutor = new MysqlQueryExecutor(connector);
        updateExecutor = new MysqlUpdateExecutor(connectorTable);
        logger.info("finding start position......");
        startPosition = findStartPosition();
        tableMetaCache = new TableMetaCache(connectorTable);
        eventParser = new LogEventConvert();
        eventParser.setTableMetaCache(tableMetaCache);
        eventParser.filterMap.put("canal_test.test", "test");
    }

    private EntryPosition findStartPosition() throws IOException {
        ResultSetPacket resultSetPacket = queryExecutor.query("show master status");
        List<String> fields = resultSetPacket.getFieldValues();
        if(CollectionUtils.isEmpty(fields)) {
            throw new NullPointerException("show master status failed!");
        }
        return new EntryPosition(fields.get(0), Long.valueOf(fields.get(1)));
    }

    private void binlogDump() throws Exception {
        logger.info("set the binlog configuration for the binlog dump");
        updateExecutor.update("set wait_timeout=9999999");
        updateExecutor.update("set net_write_timeout=1800");
        updateExecutor.update("set net_read_timeout=1800");
        updateExecutor.update("set names 'binary'");//this will be my try to test no binary
        updateExecutor.update("set @master_binlog_checksum= '@@global.binlog_checksum'");
        updateExecutor.update("SET @mariadb_slave_capability='" + LogEvent.MARIA_SLAVE_CAPABILITY_MINE + "'");
        //send binlog dump packet and mysql will establish a binlog dump thread
        logger.info("send the binlog dump packet to mysql , let mysql set up a binlog dump thread in mysql");
        BinlogDumpCommandPacket binDmpPacket = new BinlogDumpCommandPacket();
        binDmpPacket.binlogFileName = startPosition.getJournalName();
        binDmpPacket.binlogPosition = startPosition.getPosition();
        binDmpPacket.slaveServerId = slaveId;
        byte[] dmpBody = binDmpPacket.toBytes();
        HeaderPacket dmpHeader = new HeaderPacket();
        dmpHeader.setPacketBodyLength(dmpBody.length);
        dmpHeader.setPacketSequenceNumber((byte) 0x00);
        PacketManager.write(connector.getChannel(), new ByteBuffer[]{ByteBuffer.wrap(dmpHeader.toBytes()), ByteBuffer.wrap(dmpBody)});
        //initialize the mysql.dbsync to fetch the binlog data
        logger.info("initialize the mysql.dbsync class");
        fetcher = new DirectLogFetcherChannel(connector.getReceiveBufferSize());
        fetcher.start(connector.getChannel());
        decoder = new LogDecoder(LogEvent.UNKNOWN_EVENT, LogEvent.ENUM_END_EVENT);
        context = new LogContext();
        while (fetcher.fetch()) {
            LogEvent event = decoder.decode(fetcher, context);
            if(event == null) {
                logger.error("event is null!!");
                return;
            }
            printEvent(event);
        }
    }

    private void printEvent(LogEvent event) throws Exception {
        CanalEntry.Entry entry = eventParser.parse(event);
        if(entry == null) return;
        CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
        if(rowChange.getIsDdl()) {
            logger.info("--------------------------------------------------entry----------------------------------------------------");
            logger.info("ddl : " + rowChange.getSql());
        } else if(entry.getEntryType() == CanalEntry.EntryType.ROWDATA) {
            logger.info("--------------------------------------------------entry----------------------------------------------------");
            logger.info("dml : " + rowChange.getSql());
        } else {
            return;
        }
        logger.info("dbname.tbname : " + entry.getHeader().getSchemaName() + "." + entry.getHeader().getTableName());
        logger.info("position : " + entry.getHeader().getLogfileName() + "#" + entry.getHeader().getLogfileOffset());
    }

    public void start() throws Exception {
        preDump();
        binlogDump();
    }

}