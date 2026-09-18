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
import java.util.Arrays;
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

    /** Capacity of the cistern, derived from its geometry. */
    private static final double CAPACITY_LITERS = Math.PI * Math.pow(R, 2) * MAX_HEIGHT_M * 1000;

    /** Number of ADC conversions to take per reading. */
    private static final int SAMPLES = 16;

    /** Interval between readings. */
    private static final long READ_INTERVAL_MS = 30000L;

    /** Connection timeout, in seconds. */
    private static final int CONNECTION_TIMEOUT_S = 10;

    private static final Properties readConfig() throws IOException {
        final Properties props = new Properties();

        try (final FileInputStream stream = new FileInputStream("cistern.properties")) {
            props.load(stream);
        }

        return props;
    }

    /**
     * Read the sensor voltage, filtered.
     *
     * A single conversion carries around 4 mV of noise from the current loop, which the
     * 7670 L/V transfer function below turns into some 30 liters of scatter. That noise is
     * zero mean, so the median of a burst of conversions removes it without the lag a filter
     * spanning publish cycles would introduce. The median rather than the mean so that a
     * single outlier cannot move the result.
     *
     * @param   adc     The ADC to read from.
     *
     * @return  The median of {@link #SAMPLES} conversions, in volts.
     */
    private static final double readVoltage(final ADC adc) {
        final double[] samples = new double[SAMPLES];

        for (int sample = 0; sample < SAMPLES; sample++) {
            samples[sample] = adc.voltage(SENSOR_CHANNEL);
        }

        Arrays.sort(samples);

        return (samples[(SAMPLES - 1) / 2] + samples[SAMPLES / 2]) / 2.0;
    }

    private static final double readLiters(final ADC adc) {
        final double voltage = readVoltage(adc);
        final double height = ((voltage - VOLTAGE_MIN) / VOLTAGE_RANGE) * MAX_HEIGHT_M;

        return Math.clamp((Math.PI * Math.pow(R, 2) * height) * 1000, 0.0, CAPACITY_LITERS);
    }

    /**
     * The options to connect to the broker with.
     *
     * @return  The connect options.
     */
    private static final MqttConnectOptions connectOptions() {
        final MqttConnectOptions options = new MqttConnectOptions();

        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(CONNECTION_TIMEOUT_S);

        return options;
    }

    /**
     * Publish a reading, connecting first if the client is not connected.
     *
     * The client stays connected between readings, so in the normal case this only publishes.
     * Automatic reconnect only engages once a first connect has succeeded, so a broker that
     * was not up when we started still has to be picked up here.
     *
     * @param   mqttClient  The client to publish on.
     * @param   options     The options to connect with.
     * @param   liters      The reading to publish.
     */
    private static final void publish(final IMqttClient mqttClient,
                                      final MqttConnectOptions options,
                                      final int liters) {
        try {
            if (!mqttClient.isConnected()) {
                logger.info("Connecting to MQTT broker : {}", mqttClient.getServerURI());

                mqttClient.connect(options);
            }

            final MqttMessage message = new MqttMessage();
            message.setPayload(String.valueOf(liters).getBytes(StandardCharsets.UTF_8));
            message.setRetained(false);

            mqttClient.publish(TOPIC, message);
        } catch (MqttException e) {
            logger.error(String.format("Error publishing to MQTT : %s", e.getMessage()), e);
        }
    }

    /**
     * Close the MQTT client, releasing its threads and its persistence directory.
     *
     * @param   mqttClient  The client to close, may be null.
     */
    private static final void close(final IMqttClient mqttClient) {
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }

                mqttClient.close();
            } catch (MqttException e) {
                logger.error(String.format("Error closing MQTT client : %s", e.getMessage()), e);
            }
        }
    }

    public static void main(String[] args) {
        I2CDev adcI2CDevice = null;
        IMqttClient mqttClient = null;

        try {
            final Properties config = readConfig();

            adcI2CDevice = new I2CDevImpl(I2C_DEVICE);
            adcI2CDevice.open();

            final ADS1115 adc = new ADS1115(adcI2CDevice, SENSOR_ADDRESS);

            final MqttConnectOptions options = connectOptions();

            mqttClient = new MqttClient(config.getProperty(PROP_MQTT_HOST), PUBLISHER_ID);

            while (true) {
                final int liters = Double.valueOf(readLiters(adc)).intValue();

                logger.info("Liters in cistern : {}", liters);

                publish(mqttClient, options, liters);

                Thread.sleep(READ_INTERVAL_MS);
            }
        } catch (IOException e) {
            logger.error(String.format("Error reading configuration : %s", e.getMessage()), e);
        } catch (MqttException e) {
            logger.error(String.format("Could not create MQTT client : %s", e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close(mqttClient);

            if (adcI2CDevice != null && adcI2CDevice.isOpen()) {
                adcI2CDevice.close();
            }
        }
    }
}
