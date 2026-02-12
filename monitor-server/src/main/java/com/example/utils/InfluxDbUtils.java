package com.example.utils;

import com.alibaba.fastjson2.JSONObject;
import com.example.entity.dto.RuntimeData;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class InfluxDbUtils {
    @Value("${spring.influx.url}")
    String url;
    @Value("${spring.influx.user}")
    String user;
    @Value("${spring.influx.password}")
    String password;
    @Value("${spring.influx.bucket}")
    String BUCKET;
    @Value("${spring.influx.organization}")
    String ORG;

    private InfluxDBClient client;
    private WriteApi writeApi;

    @PostConstruct
    public void init() {
        client = InfluxDBClientFactory.create(url, user, password.toCharArray());
        WriteOptions writeOptions = WriteOptions.builder()
                .batchSize(100)
                .flushInterval(5000)
                .bufferLimit(10000)
                .maxRetries(3)
                .retryInterval(2000)
                .build();
        writeApi = client.makeWriteApi(writeOptions);
        writeApi.listenEvents(com.influxdb.client.write.events.WriteErrorEvent.class, event -> {
            log.error("InfluxDB异步写入失败: {}", event.getThrowable().getMessage());
        });
    }

    @PreDestroy
    public void close() {
        if (writeApi != null) {
            writeApi.close();
        }
        if (client != null) {
            client.close();
        }
    }

    public void writeRuntimeData(int clientId, RuntimeDetailVO vo) {
        RuntimeData data = new RuntimeData();
        BeanUtils.copyProperties(vo, data);
        data.setClientId(clientId);
        data.setTimestamp(new Date(vo.getTimestamp()).toInstant());
        writeApi.writeMeasurement(BUCKET, ORG, WritePrecision.NS, data);
    }

    public RuntimeHistoryVO readRuntimeHistory(int clientId) {
        RuntimeHistoryVO vo = new RuntimeHistoryVO();
        String query = """
                from(bucket: "%s")
                |> range(start: %s)
                |> filter(fn: (r) => r["_measurement"] == "runtime")
                |> filter(fn: (r) => r["clientId"] == "%s")
                """;
        String format = String.format(query, BUCKET, "-1h", clientId);
        List<FluxTable> tables = client.getQueryApi().query(format, ORG);
        int size = tables.size();
        if (size == 0) return vo;
        List<FluxRecord> records = tables.get(0).getRecords();
        for (int i = 0; i < records.size(); i++) {
            JSONObject object = new JSONObject();
            object.put("timestamp", records.get(i).getTime());
            for (int j = 0; j < size; j++) {
                FluxRecord record = tables.get(j).getRecords().get(i);
                object.put(record.getField(), record.getValue());
            }
            vo.getList().add(object);
        }
        return vo;
    }
}
