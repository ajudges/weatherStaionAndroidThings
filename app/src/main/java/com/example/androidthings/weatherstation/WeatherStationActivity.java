/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.weatherstation;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.rainbowhat.RainbowHat;
import com.google.android.things.contrib.driver.bmx280.Bmx280SensorDriver;
import com.google.android.things.iotcore.ConnectionParams;
import com.google.android.things.iotcore.IotCoreClient;
import com.google.android.things.iotcore.TelemetryEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

public class WeatherStationActivity extends Activity {
    private static final String TAG = WeatherStationActivity.class.getSimpleName();
    private float curr_temp = 30;

    // Default LED brightness
    private static final int LEDSTRIP_BRIGHTNESS = 1;
    private AlphanumericDisplay mDisplay;
    private Apa102 mLedstrip;
    private Bmx280SensorDriver mEnvironmentalSensorDriver;
    private SensorManager mSensorManager;
    private IotCoreClient client;
    private Handler mHandler = new Handler();

    // Add a helper function for converting from an InputStream to byte array
    private static byte[] inputStreamToBytes(InputStream is) throws IOException{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        return buffer.toByteArray();
    }

    //Add a new Runnable class to the WeatherStationActivity that will be used for posting data to PubSub via Cloud IoT Core.
    private Runnable mTempReportRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG,"Publishing telemetry event");

            String payload = String.format("{\"temperature\": %.2f}", curr_temp);
            TelemetryEvent event = new TelemetryEvent(payload.getBytes(),
                    null, TelemetryEvent.QOS_AT_LEAST_ONCE);
            client.publishTelemetry(event);
            Log.d(TAG,"Successfully published");

            mHandler.postDelayed(mTempReportRunnable, 2000); // Delay 2 secs, repost temp
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Weather Station Started");

        mSensorManager = getSystemService(SensorManager.class);

        // Configure the Cloud IoT Connector --
        int pkId = getResources().getIdentifier("privatekeylox", "raw", getPackageName());
        try {
            if (pkId != 0) {
                InputStream privateKey = getApplicationContext()
                        .getResources().openRawResource(pkI);
                byte[] keyBytes = inputStreamToBytes(privateKey);

                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                KeyPair keys = new KeyPair(null, kf.generatePrivate(spec));

                // Configure Cloud IoT Core project information
                ConnectionParams connectionParams = new ConnectionParams.Builder()
                        .setProjectId("myproject10983")
                        .setRegistry("my-registry", "us-central1")
                        .setDeviceId("my-device")
                        .build();

                // Initialize the IoT Core client
                client = new IotCoreClient.Builder()
                        .setConnectionParams(connectionParams)
                        .setKeyPair(keys)
                        //.setOnConfigurationListener(this::onConfigurationReceived)
                        .build();

                // Connect to Cloud IoT Core
                client.connect();

                mHandler.post(mTempReportRunnable);
            }
        } catch (InvalidKeySpecException ikse) {
            Log.e(TAG, "INVALID Key spec", ikse);
        } catch (NoSuchAlgorithmException nsae) {
            Log.e(TAG, "Algorithm not supported", nsae);
        } catch (IOException ioe) {
            Log.e(TAG, "Could not load key from file", ioe);
        }



        // Initialize temperature and pressure sensors
        try {
            mEnvironmentalSensorDriver = RainbowHat.createSensorDriver();
            //Register the drivers with the framework
            mEnvironmentalSensorDriver.registerPressureSensor();
            mEnvironmentalSensorDriver.registerTemperatureSensor();
            Log.d(TAG, "Initialized I2C BMP280");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing BMP280", e);
        }

        // Initialize 14-segment display
        try {
            mDisplay = RainbowHat.openDisplay();
            mDisplay.setEnabled(true);
            mDisplay.display("1234");
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing display", e);
        }

        // Initialize LED strip
        try {
            mLedstrip = RainbowHat.openLedStrip();
            mLedstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
            int[] colors = new int[7];
            Arrays.fill(colors, Color.GREEN);
            mLedstrip.write(colors);
            //Because of a known APA102 issue, write the initial value twice
            mLedstrip.write(colors);
            Log.d(TAG, "Initialized SPI LedStrip");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing LedStrip", e);
        }

    }

    @Override
    protected void onStart() {
        super.onStart();
        //TODO: Register for sensor events here
        //Register the BMP280 temperature sensor
        Sensor temperature = mSensorManager
                .getDynamicSensorList(Sensor.TYPE_AMBIENT_TEMPERATURE).get(0);
        mSensorManager.registerListener(mSensorEventListener, temperature,
                SensorManager.SENSOR_DELAY_NORMAL);

        //Register the BMP280 pressure sensor
        Sensor pressure = mSensorManager
                .getDynamicSensorList(Sensor.TYPE_PRESSURE).get(0);
        mSensorManager.registerListener(mSensorEventListener, pressure,
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onStop() {
        super.onStop();

        //TODO: Unregister for sensor events here
        mSensorManager.unregisterListener(mSensorEventListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mEnvironmentalSensorDriver != null) {
            try {
                mEnvironmentalSensorDriver.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing sensors", e);
            } finally {
                mEnvironmentalSensorDriver = null;
            }
        }

        if (mDisplay != null) {
            try {
                mDisplay.clear();
                mDisplay.setEnabled(false);
                mDisplay.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing display", e);
            } finally {
                mDisplay = null;
            }
        }

        if (mLedstrip != null) {
            try {
                mLedstrip.setBrightness(0);
                mLedstrip.write(new int[7]);
                mLedstrip.close();
            } catch (IOException e) {
                Log.e(TAG, "Error shutting strip", e);
            } finally {
                mLedstrip = null;
            }
        }
        // clean up Cloud publisher.
        if (client != null && client.isConnected()) {
            client.disconnect();


        }
    }

    /**
     * Update the 7-segment display with the latest temperature value.
     *
     * @param temperature Latest temperature value.
     */
    private void updateTemperatureDisplay(float temperature) {
        if (mDisplay != null) {
            try {
                mDisplay.display(temperature);
                curr_temp = temperature;
            } catch (IOException e) {
                Log.e(TAG, "Error updating display", e);
            }
        }

    }

    /**
     * Update LED strip based on the latest pressure value.
     *
     * @param pressure Latest pressure value.
     */
    private void updateBarometerDisplay(float pressure) {
        if (mDisplay != null) {
            try {
                Log.d(TAG, Float.toString(pressure));
                int[] colors = RainbowUtil.getWeatherStripColors(pressure);
                Log.d(TAG, "Color value is" + Arrays.toString(colors));
                mLedstrip.write(colors);
            } catch (IOException e) {
                Log.e(TAG, "Error updating ledstrip", e);
            }
        }
        //TODO: Add code to send color data to the LED strip
    }



    // Callback when SensorManager delivers new data
    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            final float value = event.values[0];

            if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) {
                updateTemperatureDisplay(value);
            }
            if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
                updateBarometerDisplay(value);
                Log.d(TAG, "Hey, Just changed pressure value");
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            Log.d(TAG, "Accuracy changed " + accuracy);
        }
    };

}
