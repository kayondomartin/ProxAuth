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
import android.os.AsyncTask;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.estimote.sdk.SystemRequirementsChecker;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
 import com.example.martin.proxauth.LineWorks.*;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView
            rssiTextView,
            accelXTextView, accelYTextView, accelZTextView,
            gyroXTextView, gyroYTextView, gyroZTextView, correlationTextView;
    private Button startStopButton;
    private ProgressBar progressBar;

    private SensorManager sensorManager;
    private Map<String, Sensor> sensors;

    private BluetoothManager btManager;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;

    private static List<Point> accelerometerPoints;
    private static List<Point> rssi_accelPoints;
    private static List<Point> gyroPoints;

    List<Line> accelLine;
    List<Line> gyroLine;
    List<Line> rssi_accelLine;

    List<Line> smoothedAccelLine;
    List<Line> smoothedGyroLine;
    List<Line> smoothedRSSIAccelLine;

    List<Point> sampledAccel, sampledRSSI, sampledGryo, correlation;

    private static LineSmoother lineSmoother = new LineSmoother();

    public static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final ParcelUuid EDDYSTONE_SERVICE_UUID = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");
    private static final String DeviceAddress = "E1:6E:58:BC:F1:82";

    private static final ScanFilter EDDYSTONE_SCAN_FILTER = new ScanFilter.Builder()
            .setServiceUuid(EDDYSTONE_SERVICE_UUID)
            .setDeviceAddress(DeviceAddress)
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
            gyroRecordFileOutput,
            correlationFileOutput;

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
        correlationTextView = findViewById(R.id.corr_textView);
        startStopButton = findViewById(R.id.start_stop_button);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensors = new HashMap<>();
        sensors.put(Constants.ACCKey,sensorManager.getDefaultSensor(Constants.ACCType));
        sensors.put(Constants.GYROKey,sensorManager.getDefaultSensor(Constants.GYROType));

        handler = new Handler();

        btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();
        bleScanner = btAdapter.getBluetoothLeScanner();

        accelerometerPoints = new ArrayList<>();
        rssi_accelPoints = new ArrayList<>();
        gyroPoints = new ArrayList<>();

        accelLine = new ArrayList<>();
        gyroLine = new ArrayList<>();
        rssi_accelLine = new ArrayList<>();
        correlation = new ArrayList<>();


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
    }


    private void registerSensorListeners(){
        sensorManager.registerListener(this,sensors.get(Constants.ACCKey),100000);
        sensorManager.registerListener(this,sensors.get(Constants.GYROKey),100000);
    }

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            double currentTime = (double)System.currentTimeMillis();

            int rssi = result.getRssi();
            rssiTextView.setText(rssi+"");

            getRSSIAcceleration(rssi,currentTime);

        }
    };

    private static Point rssiLastUpdate = new Point();
    private static double initial_velocity = 0;

    private void getRSSIAcceleration(int rssi, double currentTime){

        double position = getRSSIDistance(rssi);

        if(rssi_accelPoints.isEmpty()){
            rssi_accelPoints.add(new Point());
            rssiLastUpdate.setX(currentTime);
            rssiLastUpdate.setY(position);

            return;
        }
        double distance = position-rssiLastUpdate.getY();
        double timeElasped = (currentTime-rssiLastUpdate.getX())/1000.0;

        if(timeElasped < 0.01){
            return;
        }


        double acceleration = 2*(distance-initial_velocity*timeElasped)/(Math.pow(timeElasped,2));
        double time = timeElasped+rssi_accelPoints.get(rssi_accelPoints.size()-1).getX();

        initial_velocity = distance/(timeElasped);

        rssiLastUpdate.setX(currentTime);
        rssiLastUpdate.setY(position);
        rssi_accelPoints.add(new Point(time,rssi));
    }

    private double getRSSIDistance(int RSSI){
        double storage = (-68.0 - (double)RSSI)/20.0;
        double pow = Math.pow(10,storage);
        return pow;
    }


    private void startScan(){
        try {
            rssiRecordFileOutput = openFileOutput(Constants.RSSI_FILENAME, MODE_PRIVATE);
            accelRecordFileOutput = openFileOutput(Constants.ACCELEROMETER_FILENAME, MODE_PRIVATE);
            gyroRecordFileOutput = openFileOutput(Constants.GYROSCOPE_FILENAME, MODE_PRIVATE);
            correlationFileOutput = openFileOutput(Constants.CORR_FILENAME,MODE_PRIVATE);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }finally {

        }

        bleScanner.startScan(SCAN_FILTERS, SCAN_SETTINGS, scanCallback);
        registerSensorListeners();
        startTimer();
        progressBar.setVisibility(View.VISIBLE);
        startStopButton.setText("STOP");
        isScanning = true;

    }

    private void updateResults(){
        UpdateAsyncTasks updateAsyncTasks = new UpdateAsyncTasks();
        updateAsyncTasks.execute(accelerometerPoints,rssi_accelPoints);

    }

    private void stopScan(){
        bleScanner.stopScan(scanCallback);
        sensorManager.unregisterListener(this);
        startStopButton.setText("START");
        stopTimer();
        progressBar.setVisibility(View.GONE);



        isScanning = false;

        for(int i=1;i<accelerometerPoints.size();i++){
            accelLine.add(new Line(accelerometerPoints.get(i-1),accelerometerPoints.get(i)));
        }

        for(int i=1;i<rssi_accelPoints.size();i++){
            rssi_accelLine.add(new Line(rssi_accelPoints.get(i-1),rssi_accelPoints.get(i)));
        }

        for(int i=1;i<gyroPoints.size();i++){
            gyroLine.add(new Line(gyroPoints.get(i-1),gyroPoints.get(i)));
        }


        smoothedAccelLine = LineSmoother.smoothLine(accelLine);
        smoothedRSSIAccelLine = LineSmoother.smoothLine(rssi_accelLine);
        smoothedGyroLine = LineSmoother.smoothLine(gyroLine);

        double end = (int) Math.min(smoothedAccelLine.get(smoothedAccelLine.size()-1).getPoint2().getX(),smoothedRSSIAccelLine.get(smoothedRSSIAccelLine.size()-1).getPoint2().getX());
        sampledAccel = LineSmoother.sample(smoothedAccelLine,3,0.1,end);
        sampledRSSI = LineSmoother.sample(smoothedRSSIAccelLine,3,0.1,end);

        double corr = LineSmoother.corrCoeff(sampledAccel,sampledRSSI);
        DecimalFormat corrRound = new DecimalFormat("0.000");

        correlationTextView.setText("Corr: "+corrRound.format(corr));

        try{
            int smoothdAccelLength = smoothedAccelLine.size();
            int sampledAccelLength = sampledAccel.size();
            for(int i=0;i<smoothdAccelLength;i++){
                String record = smoothedAccelLine.get(i).getPoint1().getX()+"\t"+smoothedAccelLine.get(i).getPoint1().getY();
                String Record = "";
                if(i<sampledAccelLength) {
                    Record = record+"\t"+sampledAccel.get(i).getX()+"\t"+sampledAccel.get(i).getY()+"\n";
                }
                else {
                    Record = record+ "\n";
                }

                accelRecordFileOutput.write(Record.getBytes());
            }
            accelRecordFileOutput.write((smoothedAccelLine.get(smoothdAccelLength-1).getPoint2().getX()+"\t"+smoothedAccelLine.get(smoothdAccelLength-1).getPoint2().getY()).getBytes());


            for(int i=0;i<smoothedRSSIAccelLine.size();i++){
                String record = smoothedRSSIAccelLine.get(i).getPoint1().getX()+"\t"+smoothedRSSIAccelLine.get(i).getPoint1().getY();
                String Record = "";
                if(i<=sampledRSSI.size()-1) {
                    Record = record+"\t"+sampledRSSI.get(i).getX()+"\t"+sampledRSSI.get(i).getY()+"\n";
                }
                else {
                    Record = record+ "\n";
                }
                rssiRecordFileOutput.write(Record.getBytes());
            }
            rssiRecordFileOutput.write((smoothedRSSIAccelLine.get(smoothedRSSIAccelLine.size()-1).getPoint2().getX()+"\t"+smoothedRSSIAccelLine.get(smoothedRSSIAccelLine.size()-1).getPoint2().getY()).getBytes());

            for(int i=0;i<smoothedGyroLine.size();i++){
                String Record = smoothedGyroLine.get(i).getPoint1().getX()+"\t"+smoothedGyroLine.get(i).getPoint1().getY()+"\n";
                gyroRecordFileOutput.write(Record.getBytes());
            }
            gyroRecordFileOutput.write((smoothedGyroLine.get(smoothedGyroLine.size()-1).getPoint2().getX()+"\t"+smoothedGyroLine.get(smoothedGyroLine.size()-1).getPoint2().getY()).getBytes());

            for(int i = 0; i<correlation.size();i++){
                Point p = correlation.get(i);
                String record = p.getX()+"\t"+p.getY()+"\n";
                correlationFileOutput.write(record.getBytes());
            }

        } catch (IOException e) {
            e.printStackTrace();
        }finally{
            try{
                rssiRecordFileOutput.close();
                accelRecordFileOutput.close();
                gyroRecordFileOutput.close();
                correlationFileOutput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }



    private void startTimer(){
        runnable = new Runnable() {
            @Override
            public void run() {
                seconds+=1000;
                handler.postDelayed(this,1000);
                updateResults();
            }
        };
        handler.postDelayed(runnable,1000);
    }

    private void stopTimer(){
        handler.removeCallbacks(runnable);
    }

    private void updateSensorRecords(int type, float[] values,long time){

        switch(type){
            case Sensor.TYPE_ACCELEROMETER:
                accelXTextView.setText(values[0]+"");
                accelYTextView.setText(values[1]+"");
                accelZTextView.setText(values[2]+"");
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyroXTextView.setText(values[0]+"");
                gyroYTextView.setText(values[1]+"");
                gyroZTextView.setText(values[2]+"");
                break;
        }

    }

    private static Point lastAccelUpdate = new Point();
    private static Point lastGyroUpdate = new Point();

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if(sensorEvent.sensor.getType() == Constants.ACCType){
            long currentTime = System.currentTimeMillis();
            if (accelerometerPoints.isEmpty()){
                accelerometerPoints.add(new Point(0,sensorEvent.values[2]));
            }else{
                double time = ((currentTime-lastAccelUpdate.getX())/1000.0) +accelerometerPoints.get(accelerometerPoints.size()-1).getX();
                accelerometerPoints.add(new Point(time, sensorEvent.values[2]));
            }

            lastAccelUpdate.setX(currentTime);
            lastAccelUpdate.setY(sensorEvent.values[2]);
            updateSensorRecords(Constants.ACCType,sensorEvent.values,currentTime);
        }
        if(sensorEvent.sensor.getType() == Constants.GYROType){
            long currentTime = System.currentTimeMillis();
            if (gyroPoints.isEmpty()){
                gyroPoints.add(new Point(0,sensorEvent.values[2]));
            }else{
                double time = ((currentTime-lastGyroUpdate.getX())/1000.0)+gyroPoints.get(gyroPoints.size()-1).getX();
                gyroPoints.add(new Point(time, sensorEvent.values[2]));
            }

            lastGyroUpdate.setX(currentTime);
            lastGyroUpdate.setY(sensorEvent.values[2]);
            updateSensorRecords(Constants.GYROType,sensorEvent.values,currentTime);
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private class UpdateAsyncTasks extends AsyncTask<List<Point>,Void,Point>{


        public UpdateAsyncTasks(){
        }

        @Override
        protected Point doInBackground(List<Point>... lists) {

            List<Line> rssiList = new ArrayList<>();
            for(int i=1; i<lists[0].size();i++){
                rssiList.add(new Line(lists[0].get(i-1),lists[0].get(i)));
            }

            List<Line> accelList = new ArrayList<>();

            for(int i=1; i<lists[1].size();i++){
                accelList.add(new Line(lists[1].get(i-1),lists[1].get(i)));
            }

            double end = (int) Math.min(rssiList.get(rssiList.size()-1).getPoint2().getX(),accelList.get(accelList.size()-1).getPoint2().getX());
            List<Point> rssiSample = LineSmoother.sample(rssiList,0,0.1,end);
            List<Point> accelSample = LineSmoother.sample(accelList,0,0.1,end);
            double correlation = LineSmoother.corrCoeff(rssiSample,accelSample);
            return new Point(end,correlation);
        }

        @Override
        protected void onPostExecute(Point point) {
            correlation.add(point);
            DecimalFormat corrRound = new DecimalFormat("0.000");
            correlationTextView.setText(corrRound.format(point.getY())+"");
        }
    }

}
