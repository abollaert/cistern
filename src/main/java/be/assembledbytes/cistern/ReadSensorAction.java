package be.assembledbytes.cistern;

import be.assembledbytes.linux.i2c.I2CDev;
import be.assembledbytes.linux.i2c.I2CDevImpl;
import be.assembledbytes.linux.sensor.ADC;
import be.assembledbytes.linux.sensor.ADS1115;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class ReadSensorAction {

    private static final Logger logger = LoggerFactory.getLogger(ReadSensorAction.class);

    private static final String I2C_DEVICE = "/dev/i2c-1";

    /** The topic to post on. */
    private static final String TOPIC = "/home/sensor/cistern/volume";

    /** The publisher ID. */
    private static final String PUBLISHER_ID = "cistern";

    private static final String PROP_MQTT_HOST = "mqtt.host";

    private static final int SENSOR_ADDRESS = 0x48;
    private static final int SENSOR_CHANNEL = 0;

    private static final double VOLTAGE_MIN = 0.48;
    private static final double VOLTAGE_MAX = 2.4;
    private static final double VOLTAGE_RANGE = VOLTAGE_MAX - VOLTAGE_MIN;
    private static final int MAX_HEIGHT_M = 3;
    private static final double R = 1.25;

    private static final Properties readConfig() throws IOException {
        final Properties props = new Properties();

        try (final FileInputStream stream = new FileInputStream("cistern.properties")) {
            props.load(stream);
        }

        return props;
    }

    private static final double readLiters(final ADC adc) {
        final double voltage = adc.voltage(SENSOR_CHANNEL);
        final double height = ((voltage - VOLTAGE_MIN) / VOLTAGE_RANGE) * MAX_HEIGHT_M;

        return height > 0.0 ?
               (Math.PI * Math.pow(R, 2) * height) * 1000 :
               0.0;
    }

    public static void main(String[] args) {
        I2CDev adcI2CDevice = null;

        try {
            final Properties config = readConfig();

            adcI2CDevice = new I2CDevImpl(I2C_DEVICE);
            adcI2CDevice.open();

            final ADS1115 adc = new ADS1115(adcI2CDevice, SENSOR_ADDRESS);

            while (true) {
                final int liters = Double.valueOf(readLiters(adc)).intValue();

                logger.info("Liters in cistern : {}", liters);

                final MqttMessage message = new MqttMessage();
                message.setPayload(String.valueOf(liters).getBytes(StandardCharsets.UTF_8));
                message.setRetained(false);

                final MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
                mqttConnectOptions.setAutomaticReconnect(true);
                mqttConnectOptions.setCleanSession(true);
                mqttConnectOptions.setConnectionTimeout(10);

                try {
                    final IMqttClient mqttClient = new MqttClient(config.getProperty(PROP_MQTT_HOST), PUBLISHER_ID);

                    mqttClient.connect();
                    mqttClient.publish(TOPIC, message);

                    mqttClient.disconnect();
                } catch (MqttException e) {
                    logger.error(String.format("Error publishing to MQTT : %s", e.getMessage()), e);
                }

                Thread.sleep(30000);
            }
        } catch (IOException e) {
            logger.error(String.format("Error reading configuration : %s", e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (adcI2CDevice != null && adcI2CDevice.isOpen()) {
                adcI2CDevice.close();
            }
        }
    }
}
