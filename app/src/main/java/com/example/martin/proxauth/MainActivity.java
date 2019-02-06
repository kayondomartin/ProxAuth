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
import android.transition.ChangeBounds;
import android.transition.ChangeImageTransform;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.estimote.sdk.SystemRequirementsChecker;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
 import com.example.martin.proxauth.LineWorks.*;

@SuppressWarnings("ALL")
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private ViewGroup transitionsContainer;
    private Button controlButton;
    private ImageView directionIconImage;
    private TextView processTextView;
    private TextView addressTextView;


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

    private static int rssiSize = 0;
    private static int accelSize = 0;

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

        transitionsContainer = findViewById(R.id.transitionsContainer);
        controlButton = findViewById(R.id.control_button);
        directionIconImage = findViewById(R.id.rotationIconView);
        processTextView = findViewById(R.id.processtextView);
        processTextView.setVisibility(View.INVISIBLE);
        addressTextView = findViewById(R.id.device_addressView);


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

        smoothedAccelLine = new ArrayList<>();
        smoothedRSSIAccelLine = new ArrayList<>();
        smoothedGyroLine = new ArrayList<>();


        if(btAdapter == null || bleScanner == null){
            Toast.makeText(this,"Either bluetooth or BLE not supported!",Toast.LENGTH_SHORT);
            finish();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    MY_PERMISSION_REQUEST_READ_CONTACTS);
        }

        controlButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                startScan();
                controlButton.setVisibility(View.INVISIBLE);
                processTextView.setVisibility(View.VISIBLE);

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

    private Point rssiInitial = null;
    private double initialTime = 0;
    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            double currentTime = (double)System.currentTimeMillis();

            int rssi = result.getRssi();
            if(rssiSize == 0){
                if(rssiInitial == null){
                    rssiInitial = new Point(0.0, rssi);
                    initialTime = currentTime/1000;
                }else {
                    Line newLine = new Line(rssiInitial, new Point((currentTime / 1000) - initialTime, rssi));
                    rssi_accelLine.add(newLine);
                    rssiSize++;
                }
            }else{
                Line newLine = new Line(rssi_accelLine.get(rssiSize++-1).getPoint2(),new Point((currentTime/1000)-initialTime,rssi));
                rssi_accelLine.add(newLine);
            }
            addressTextView.setText(result.getDevice().getAddress());
        }
    };

    /*private static Point rssiLastUpdate = new Point();
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

*/
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
        isScanning = true;

    }

    private void updateResults(){
        UpdateAsyncTasks updateAsyncTasks = new UpdateAsyncTasks();
        updateAsyncTasks.execute();

    }

    private void stopScan(){
        bleScanner.stopScan(scanCallback);
        sensorManager.unregisterListener(this);
        stopTimer();



        isScanning = false;

        try{
            int sampleSize = sampledRSSI.size();

            for(int i=0;i<sampleSize;i++){
                Point rssiPoint = sampledRSSI.get(i);
                Point accelPoint = sampledAccel.get(i);
                String rssiRecord = rssiPoint.getX()+"\t"+rssiPoint.getY()+"\n";
                String accelRecord = accelPoint.getX()+"\t"+accelPoint.getY()+"\n";
                accelRecordFileOutput.write(accelRecord.getBytes());
                rssiRecordFileOutput.write(rssiRecord.getBytes());
            }

           /* for(int i=0;i<smoothedGyroLine.size();i++){
                String Record = smoothedGyroLine.get(i).getPoint1().getX()+"\t"+smoothedGyroLine.get(i).getPoint1().getY()+"\n";
                gyroRecordFileOutput.write(Record.getBytes());
            }
            gyroRecordFileOutput.write((smoothedGyroLine.get(smoothedGyroLine.size()-1).getPoint2().getX()+"\t"+smoothedGyroLine.get(smoothedGyroLine.size()-1).getPoint2().getY()).getBytes());
            */
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

            processTextView.setVisibility(View.INVISIBLE);
            controlButton.setVisibility(View.VISIBLE);
        }

    }

    private void motionGesture(boolean rotate, boolean tofro, boolean expanded, float degrees){
        if(tofro){
            TransitionManager.beginDelayedTransition(transitionsContainer, new TransitionSet()
                    .addTransition(new ChangeBounds())
                    .addTransition(new ChangeImageTransform()));

            expanded = !expanded;
            ViewGroup.LayoutParams params = directionIconImage.getLayoutParams();
            params.height = expanded? ViewGroup.LayoutParams.MATCH_PARENT:
                    ViewGroup.LayoutParams.WRAP_CONTENT;
            directionIconImage.setLayoutParams(params);

            directionIconImage.setScaleType(expanded? ImageView.ScaleType.FIT_END:
                    ImageView.ScaleType.FIT_CENTER);
        }else if(rotate){
            final RotateAnimation rotateAnimation = new RotateAnimation(-degrees, degrees,
                    RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                    RotateAnimation.RELATIVE_TO_SELF,0.5f);

            rotateAnimation.setDuration(0);
            rotateAnimation.setFillAfter(true);
            directionIconImage.startAnimation(rotateAnimation);
        }
    }


    boolean expanded;
    float degrees = 45;
    int count = 0;
    String process = "Authenticating";
    private void startTimer(){
        step = 1;
        runnable = new Runnable() {
            @Override
            public void run() {
                seconds+=1000;
                if(step == 1){
                    expanded = !expanded;
                    motionGesture(false,true,expanded, degrees);
                }else{
                    degrees = -degrees;
                    motionGesture(true,false,expanded, degrees);
                }
                if(count++ == 2){
                    count = 0;
                    process = "Authenticating";
                }
                process+=" .";
                processTextView.setText(seconds+"");

                updateResults();
                handler.postDelayed(this,1000);

            }
        };
        handler.postDelayed(runnable,1000);
    }

    private void stopTimer(){
        seconds = 0;
        handler.removeCallbacks(runnable);
    }

    private static Point lastAccelUpdate = new Point();
    private static Point lastGyroUpdate = new Point();

    private double initialAccelTime = 0;
    private Point initialAccel = null;

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        double currentTime = (double) System.currentTimeMillis();
        if(sensorEvent.sensor.getType() == Constants.ACCType){
           if(accelSize == 0){
               if(initialAccel == null){
                   initialAccelTime = currentTime;
                   initialAccel = new Point(0.0,sensorEvent.values[2]);
               }else{
                   Line newLine = new Line(initialAccel,new Point((currentTime-initialAccelTime)/1000,sensorEvent.values[2]));
                   accelLine.add(newLine);
                   accelSize++;
               }
           }else{
               Line newLine = new Line(accelLine.get(accelSize++-1).getPoint2(),new Point((currentTime-initialAccelTime)/1000,sensorEvent.values[2]));
               accelLine.add(newLine);
           }

        }
        if(sensorEvent.sensor.getType() == Constants.GYROType){
            if (gyroPoints.isEmpty()){
                gyroPoints.add(new Point(0,sensorEvent.values[2]));
            }else{
                double time = ((currentTime-lastGyroUpdate.getX())/1000.0)+gyroPoints.get(gyroPoints.size()-1).getX();
                gyroPoints.add(new Point(time, sensorEvent.values[2]));
            }

            lastGyroUpdate.setX(currentTime);
            lastGyroUpdate.setY(sensorEvent.values[2]);
            //updateSensorRecords(Constants.GYROType,sensorEvent.values,currentTime);
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private static int lastAccelAdded = 0;
    private static int lastRssiAdded = 0;
    private static int step = 0;

    private class UpdateAsyncTasks extends AsyncTask<Void, Double,Double>{

        @Override
        protected Double doInBackground(Void... voids) {
            if(step == 1) {

                smoothedRSSIAccelLine = LineSmoother.smoothLine(rssi_accelLine);
                smoothedAccelLine = LineSmoother.smoothLine(accelLine);

                double end = (int) Math.min(smoothedAccelLine.get(smoothedAccelLine.size() - 1).getPoint2().getX(), smoothedRSSIAccelLine.get(smoothedRSSIAccelLine.size() - 1).getPoint2().getX());
                sampledAccel = LineSmoother.sample(smoothedAccelLine, 0, 0.1, end);
                sampledRSSI = LineSmoother.sample(smoothedRSSIAccelLine, 0, 0.1, end);

                double corr = LineSmoother.corrCoeff(sampledAccel, sampledRSSI);

                return corr;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Double aDouble) {
            if(step == 1){
                correlation.add(new Point(seconds,aDouble));
            }

            if(aDouble == null){
                stopScan();
            }
            if((seconds<=10000) && (seconds >=6000) && aDouble >= 0.5){
                Toast.makeText(MainActivity.this,"To and Fro Authenticated!!",Toast.LENGTH_SHORT).show();
                step = 2;

            }else if(seconds> 10000 && step == 1){
                Toast.makeText(MainActivity.this,"Authentication Failed!!",Toast.LENGTH_SHORT).show();
                stopScan();
            }else if(seconds>10000){
                stopScan();
            }
        }
    }



}
