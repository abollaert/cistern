package be.assembledbytes.cistern;

import be.assembledbytes.linux.i2c.I2CDev;
import be.assembledbytes.linux.i2c.I2CDevImpl;
import be.assembledbytes.linux.sensor.ADC;
import be.assembledbytes.linux.sensor.ADS1115;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;

public class ReadSensorAction {

    private static final Logger logger = LoggerFactory.getLogger(ReadSensorAction.class);

    private static final String I2C_DEVICE = "/dev/i2c-1";

    private static final String PROP_DB_URL = "database.url";
    private static final String PROP_DB_USER = "database.user";
    private static final String PROP_DB_PASSWORD = "database.password";

    private static final int SENSOR_ADDRESS = 0x48;
    private static final int SENSOR_CHANNEL = 0;

    private static final double VOLTAGE_MIN = 0.48;
    private static final double VOLTAGE_MAX = 2.4;
    private static final double VOLTAGE_RANGE = VOLTAGE_MAX - VOLTAGE_MIN;
    private static final int MAX_HEIGHT_M = 3;
    private static final double R = 0.14;

    private static final Properties readDatabaseConfig() throws IOException {
        final Properties props = new Properties();

        props.load(new FileInputStream("database.properties"));

        return props;
    }

    private static final double readLiters() {
        final I2CDev i2cDevice = new I2CDevImpl(I2C_DEVICE);

        try {
            i2cDevice.open();

            final ADC adc = new ADS1115(i2cDevice, SENSOR_ADDRESS);
            final double voltage = adc.voltage(SENSOR_CHANNEL);

            final double height = ((voltage - VOLTAGE_MIN) / VOLTAGE_RANGE) * MAX_HEIGHT_M;
            return height > 0.0 ?
                   (Math.PI * Math.pow(R, 2) * height) * 1000 :
                   0.0;
        } finally {
            i2cDevice.close();
        }
    }

    private static final void storeData(final double liters) {
        Connection connection = null;

        try {
            final Properties dbConfig = readDatabaseConfig();

            connection = DriverManager.getConnection(dbConfig.getProperty(PROP_DB_URL),
                                                     dbConfig.getProperty(PROP_DB_USER),
                                                     dbConfig.getProperty(PROP_DB_PASSWORD));

            final PreparedStatement statement = connection.prepareStatement("insert into measurements(timestamp, liters) values (now(), ?)");
            statement.setDouble(1, liters);

            statement.executeUpdate();
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Error loading database configuration : [%s]",
                                                          e.getMessage()),
                                            e);
        } catch (SQLException e) {
            throw new IllegalStateException(String.format("Error storing data : [%s]",
                                                          e.getMessage()),
                                            e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // :shrug:
                }
            }
        }
    }

    public static void main(String[] args) {
        final double liters = readLiters();

        logger.info("Measurement : [{}] liters.", liters);

        storeData(liters);
    }
}
