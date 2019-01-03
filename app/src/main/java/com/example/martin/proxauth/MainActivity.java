package com.example.martin.proxauth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.estimote.sdk.SystemRequirementsChecker;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView
            rssiTextView,
            accelXTextView, accelYTextView, accelZTextView,
            gyroXTextView, gyroYTextView, gyroZTextView;
    private Button startStopButton;

    private SensorManager sensorManager;
    private Map<String, Sensor> sensors;

    private BluetoothManager btManager;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;

    public static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final ParcelUuid EDDYSTONE_SERVICE_UUID = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");

    private static final ScanFilter EDDYSTONE_SCAN_FILTER = new ScanFilter.Builder()
            .setServiceUuid(EDDYSTONE_SERVICE_UUID)
            .build();

    private ScanSettings SCAN_SETTINGS =
            new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build();

    private List<ScanFilter> SCAN_FILTERS = buildScanFilters();

    private static final int MY_PERMISSION_REQUEST_READ_CONTACTS = 1;

    private static List<ScanFilter> buildScanFilters(){
        List<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(EDDYSTONE_SCAN_FILTER);
        return scanFilters;
    }

    private static long seconds = 0;
    private static boolean isScanning = false;

    private FileOutputStream
            rssiRecordFileOutput,
            accelRecordFileOutput,
            gyroRecordFileOutput;

    private Handler handler;
    private Runnable runnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rssiTextView = findViewById(R.id.rssi_textView);
        accelXTextView = findViewById(R.id.accel_x_textView);
        accelYTextView = findViewById(R.id.accel_y_textView);
        accelZTextView = findViewById(R.id.accel_z_textView);
        gyroXTextView = findViewById(R.id.gyro_x_textView);
        gyroYTextView = findViewById(R.id.gyro_y_textView);
        gyroZTextView = findViewById(R.id.gyro_z_textView);
        startStopButton = findViewById(R.id.start_stop_button);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensors = new HashMap<>();
        sensors.put(Constants.ACCKey,sensorManager.getDefaultSensor(Constants.ACCType));
        sensors.put(Constants.GYROKey,sensorManager.getDefaultSensor(Constants.GYROType));

        handler = new Handler();

        btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();
        bleScanner = btAdapter.getBluetoothLeScanner();

        if(btAdapter == null || bleScanner == null){
            Toast.makeText(this,"Either bluetooth or BLE not supported!",Toast.LENGTH_SHORT);
            finish();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    MY_PERMISSION_REQUEST_READ_CONTACTS);
        }

        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(isScanning){
                    stopScan();
                }else{
                    startScan();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SystemRequirementsChecker.checkWithDefaultDialogs(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    private void clearFiles(String ... fileNames) throws FileNotFoundException {
        for(String name: fileNames){
            PrintWriter printWriter = new PrintWriter(name);
            printWriter.close();
        }
    }

    private void registerSensorListeners(){
        sensorManager.registerListener(this,sensors.get(Constants.ACCKey),sensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this,sensors.get(Constants.GYROKey),sensorManager.SENSOR_DELAY_NORMAL);
    }

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            int rssi = result.getRssi();
            String record = seconds+"\t"+rssi+"\n";
            rssiTextView.setText(rssi);

            try{
                rssiRecordFileOutput.write(record.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }finally {

            }
        }
    };

    private void startScan(){
        try {
            rssiRecordFileOutput = openFileOutput(Constants.RSSI_FILENAME, MODE_APPEND);
            accelRecordFileOutput = openFileOutput(Constants.ACCELEROMETER_FILENAME, MODE_APPEND);
            gyroRecordFileOutput = openFileOutput(Constants.GYROSCOPE_FILENAME, MODE_APPEND);

            clearFiles(Constants.ACCELEROMETER_FILENAME,Constants.GYROSCOPE_FILENAME, Constants.RSSI_FILENAME);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }finally {

        }

        bleScanner.startScan(SCAN_FILTERS, SCAN_SETTINGS, scanCallback);
        registerSensorListeners();
        startTimer();
        startStopButton.setText("STOP");
        isScanning = true;

    }

    private void stopScan(){
        bleScanner.stopScan(scanCallback);
        sensorManager.unregisterListener(this);
        startStopButton.setText("START");
        stopTimer();

        try{
            rssiRecordFileOutput.close();
            accelRecordFileOutput.close();
            gyroRecordFileOutput.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        isScanning = false;
    }

    private void startTimer(){
        runnable = new Runnable() {
            @Override
            public void run() {
                seconds+=100;
                handler.postDelayed(this,100);
            }
        };
        handler.postDelayed(runnable,100);
    }

    private void stopTimer(){
        handler.removeCallbacks(runnable);
    }

    private void updateSenosrRecords(int type, float[] values){
        String record = seconds + "\t"+values[0]+"\t"+values[1]+"\t"+values[2]+"\n";
        try{
            switch(type){
                case Sensor.TYPE_ACCELEROMETER:
                    accelRecordFileOutput.write(record.getBytes());
                    accelXTextView.setText(values[0]+"");
                    accelYTextView.setText(values[1]+"");
                    accelZTextView.setText(values[2]+"");
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    gyroRecordFileOutput.write(record.getBytes());
                    gyroXTextView.setText(values[0]+"");
                    gyroYTextView.setText(values[1]+"");
                    gyroZTextView.setText(values[2]+"");
                    break;
            }
        }catch (FileNotFoundException e){
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {

        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if(sensorEvent.sensor.getType() == Constants.ACCType){
            updateSenosrRecords(Constants.ACCType,sensorEvent.values);
        }
        if(sensorEvent.sensor.getType() == Constants.GYROType){
            updateSenosrRecords(Constants.GYROType,sensorEvent.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
