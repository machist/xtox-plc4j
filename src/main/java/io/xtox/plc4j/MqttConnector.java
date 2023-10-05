package io.xtox.plc4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3RxClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3PublishResult;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.xtox.plc4j.model.Configuration;
import io.xtox.plc4j.model.PlcTagConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.exceptions.PlcException;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MqttConnector {

    private static final Logger logger = LoggerFactory.getLogger(MqttConnector.class);

    private Configuration config;

    private MqttConnector(String propsPath) {
        if (StringUtils.isEmpty(propsPath)) {
            logger.error("Empty configuration file parameter");
            throw new IllegalArgumentException("Empty configuration file parameter");
        }

        final File propsFile = new File(propsPath);
        if (!(propsFile.exists() && propsFile.isFile())) {
            logger.error("Invalid configuration file {}", propsFile.getPath());
            throw new IllegalArgumentException("Invalid configuration file " + propsFile.getPath());
        }

        final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            config = mapper.readValue(propsFile, Configuration.class);
        } catch (IOException e) {
            logger.error("Error parsing configuration", e);
        }
    }

    private void run() throws PlcException {
        // Create a new MQTT client.
        final Mqtt3RxClient client = MqttClient.builder()
                .identifier(UUID.randomUUID().toString())
                .serverHost(config.getMqttConfig().getServerHost())
                .serverPort(config.getMqttConfig().getServerPort())
                .useMqttVersion3()
                .buildRx();

        // Connect to the MQTT broker.
        final Single<Mqtt3ConnAck> connAckSingle = client.connect().timeout(10, TimeUnit.SECONDS);

        // Connect to the PLC.
        try (PlcConnection plcConnection = PlcDriverManager.getDefault().getConnectionManager().getConnection(config.getPlcConfig().getConnection())) {

            // Check if this connection support reading of data.
            if (!plcConnection.getMetadata().canRead()) {
                System.err.println("This connection doesn't support reading.");
                return;
            }

            // Create a new read request.
            PlcReadRequest.Builder builder = plcConnection.readRequestBuilder();
            for (PlcTagConfig tagConfig : config.getPlcConfig().getPlcTags()) {
                builder = builder.addTagAddress(tagConfig.getName(), tagConfig.getAddress());
            }
            PlcReadRequest readRequest = builder.build();

            // Send a message containing the PLC read response.
            Flowable<Mqtt3Publish> messagesToPublish = Flowable.generate(emitter ->
                    readRequest.execute()
                            .thenAccept(response ->
                                    emitter.onNext(
                                            Mqtt3Publish.builder()
                                                    .topic(config.getMqttConfig().getTopicName())
                                                    .qos(MqttQos.AT_LEAST_ONCE)
                                                    .payload(getPayload(response).getBytes())
                                                    .build()
                                    )
                            )
            );

            // Emit 1 message only every 100 milliseconds.
            messagesToPublish = messagesToPublish.zipWith(Flowable.interval(
                    config.getPollingInterval(), TimeUnit.MILLISECONDS), (publish, aLong) -> publish);

            final Single<Mqtt3ConnAck> connectScenario = connAckSingle
                    .doOnSuccess(connAck -> System.out.println("Connected with return code " + connAck.getReturnCode()))
                    .doOnError(throwable -> System.out.println("Connection failed, " + throwable.getMessage()));

            final Flowable<Mqtt3PublishResult> publishScenario = client.publish(messagesToPublish)
                    .doOnNext(publishResult -> System.out.println(
                            "Publish acknowledged: " + new String(publishResult.getPublish().getPayloadAsBytes())));

            connectScenario.toCompletable().andThen(publishScenario).blockingSubscribe();
        } catch (Exception e) {
            throw new PlcException("Error creating connection to " + config.getPlcConfig().getConnection(), e);
        }
    }

    private String getPayload(PlcReadResponse response) {
        final JsonObject jsonObject = new JsonObject();

        response.getTagNames().forEach(tagName -> {
            if (response.getNumberOfValues(tagName) == 1) {
                jsonObject.addProperty(tagName, response.getObject(tagName).toString());
            } else if (response.getNumberOfValues(tagName) > 1) {
                JsonArray values = new JsonArray();
                response.getAllBytes(tagName).forEach(values::add);
                jsonObject.add(tagName, values);
            }
        });
        return jsonObject.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: MqttConnector {path-to-mqtt-connector.yml}");
        }

        final MqttConnector mqttConnector = new MqttConnector(args[0]);
        mqttConnector.run();
    }

}
