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
import android.util.Log;
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

    List<Line> accelLine;
    List<Line> gyroLine;
    List<Line> rssi_accelLine;
    List<Line> rssi_gyroLine;

    List<Line> smoothedAccelLine;
    List<Line> smoothedGyroLine;
    List<Line> smoothedRSSIAccelLine;
    List<Line> smoothedRSSIGyroLine;

    List<Point> sampledAccel,
                sampledRSSIAccel,
                sampledRSSIGyro,
                sampledGyro,
                accelCorr,
                gyroCorr;

    private static LineSmoother lineSmoother = new LineSmoother();

    public static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static int rssiSize = 0;
    private static int accelSize = 0;
    private static int gyroSize = 0;

    private static long seconds = 0;
    private static boolean isScanning = false;

    private FileOutputStream
            rssiAccelRecordFileOutput,
            rssigyroRecordFileOutput,
            accelRecordFileOutput,
            gyroRecordFileOutput,
            accelCorrFileOutput,
            gyroCorrFileOutput;

    private Handler handler;
    private Runnable runnable;

    private static final int MY_PERMISSION_REQUEST_READ_CONTACTS = 1;

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


    private static List<ScanFilter> buildScanFilters(){
        List<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(EDDYSTONE_SCAN_FILTER);
        return scanFilters;
    }

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

        accelLine = new ArrayList<>();
        gyroLine = new ArrayList<>();
        rssi_accelLine = new ArrayList<>();

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

            if(step == 0){
                rssiAccelRecordFileOutput = openFileOutput(Constants.RSSI_ACCEL_FILENAME,MODE_PRIVATE);
                accelCorrFileOutput = openFileOutput(Constants.ACCELCORR_FILENAME,MODE_PRIVATE);
                step = 1;
            }else if(step == 1){
                rssigyroRecordFileOutput = openFileOutput(Constants.RSSI_GYRO_FILENAME, MODE_PRIVATE);
                gyroCorrFileOutput = openFileOutput(Constants.GYROCORR_FILENAME, MODE_PRIVATE);
                step = 2;
            }
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

    private boolean toFroAuthenticated = false;
    private boolean rotateAuthenticated = false;

    private void stopScan(){
        bleScanner.stopScan(scanCallback);
        sensorManager.unregisterListener(this);
        stopTimer();
        seconds = 0;
        processTextView.setVisibility(View.INVISIBLE);
        controlButton.setVisibility(View.VISIBLE);
        isScanning = false;

        try {
            int i = 0;
            String record;
            if (step == 1) {
                int accelSampleSize = sampledAccel.size();
                for (; i < accelSampleSize; i++) {
                    record = sampledRSSIAccel.get(i).getX() + "\t" + sampledRSSIAccel.get(i).getY() + "\t" + sampledAccel.get(i).getY() + "\n";
                    rssiAccelRecordFileOutput.write(record.getBytes());
                }
                rssiAccelRecordFileOutput.close();
                i = 0;
                int accelCorrSize = accelCorr.size();
                for(;i<accelCorrSize;i++){
                    record = accelCorr.get(i).getX()+"\t"+accelCorr.get(i).getY()+"\n";
                    accelCorrFileOutput.write(record.getBytes());
                }
                accelCorrFileOutput.close();

                if(toFroAuthenticated){
                    step = 2;
                    Toast.makeText(MainActivity.this,"Step 1 Completed!!", Toast.LENGTH_SHORT).show();
                    controlButton.setText("Next");
                }else{
                    step = 0;
                    Toast.makeText(MainActivity.this,"Authentication Failed!!",Toast.LENGTH_SHORT).show();
                    controlButton.setText("Start");
                }
            }else if(step == 2){
                step = 0;
                int gyroSampleSize = sampledGyro.size();
                for(i=0;i<gyroSampleSize;i++){
                    record = sampledRSSIGyro.get(i).getX() + "\t" + sampledRSSIGyro.get(i).getY() + "\t" + sampledGyro.get(i).getY() + "\n";
                    rssigyroRecordFileOutput.write(record.getBytes());
                }
                rssigyroRecordFileOutput.close();
                int gyroCorrSize = gyroCorr.size();
                for(i=0;i<gyroCorrSize;i++){
                    record = gyroCorr.get(i).getX()+"\t"+gyroCorr.get(i).getY()+"\n";
                    gyroCorrFileOutput.write(record.getBytes());
                }
                gyroCorrFileOutput.close();

                if(rotateAuthenticated){
                    Toast.makeText(MainActivity.this, "Device Authenticated!!",Toast.LENGTH_SHORT).show();
                    processTextView.setText("Device Authenticated!!");;
                    processTextView.setVisibility(View.VISIBLE);
                    controlButton.setVisibility(View.INVISIBLE);
                }else{
                    Toast.makeText(MainActivity.this,"Authentication Failed!!", Toast.LENGTH_SHORT).show();
                    processTextView.setText("Authentication Failed!!");
                    processTextView.setVisibility(View.VISIBLE);
                    controlButton.setVisibility(View.INVISIBLE);
                }
            }
        }catch(IOException e){
            Log.e(MainActivity.class.getSimpleName(),e.getMessage());
        }finally {

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
            float from = directionIconImage.getRotation();
            final RotateAnimation rotateAnimation = new RotateAnimation(from, degrees,
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
        runnable = new Runnable() {
            @Override
            public void run() {
                seconds+=1000;
                if(step == 1){
                    expanded = !expanded;
                    motionGesture(false,true,expanded, degrees);
                }else if(step == 2){
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
        handler.removeCallbacks(runnable);
        motionGesture(true,false,expanded,0.0f);
    }

    private static Point lastAccelUpdate = new Point();
    private static Point lastGyroUpdate = new Point();

    private double initialAccelTime = 0;
    private Point initialAccel = null;
    private Point initialGyro = null;
    private double initialGyroTime = 0;

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        double currentTime = (double) System.currentTimeMillis();
        if(sensorEvent.sensor.getType() == Constants.ACCType && step ==1){
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
        if(sensorEvent.sensor.getType() == Constants.GYROType && step == 2){

            if(gyroSize == 0){
                if(initialGyro == null){
                    initialGyro = new Point(0.0,sensorEvent.values[2]);
                    initialGyroTime = currentTime;
                }else{
                    Line newLine = new Line(initialGyro, new Point((currentTime-initialGyroTime)/1000,sensorEvent.values[2]));
                    gyroLine.add(newLine);
                    gyroSize++;
                }
            }else{
                Line newLine = new Line(accelLine.get(gyroSize++-1).getPoint2(), new Point((currentTime-initialGyroTime)/1000,sensorEvent.values[2]));
                gyroLine.add(newLine);
            }
            lastGyroUpdate.setX(currentTime);
            lastGyroUpdate.setY(sensorEvent.values[2]);
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
                sampledRSSIAccel = LineSmoother.sample(smoothedRSSIAccelLine, 0, 0.1, end);

                double corr = LineSmoother.corrCoeff(sampledAccel, sampledRSSIAccel);

                return corr;
            }else if(step == 2){
                smoothedGyroLine = LineSmoother.smoothLine(gyroLine);
                smoothedRSSIGyroLine = LineSmoother.smoothLine(rssi_gyroLine);

                double end = (int) Math.min(smoothedGyroLine.get(smoothedGyroLine.size() - 1).getPoint2().getX(), smoothedRSSIGyroLine.get(smoothedRSSIGyroLine.size() - 1).getPoint2().getX());
                sampledGyro = LineSmoother.sample(smoothedGyroLine, 0, 0.1, end);
                sampledRSSIGyro = LineSmoother.sample(smoothedRSSIGyroLine, 0, 0.1, end);

                double corr = LineSmoother.corrCoeff(sampledGyro, sampledRSSIGyro);

                return corr;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Double aDouble) {
            if(aDouble == null){
                stopScan();
            }
            boolean authenticated = (seconds >= 7000) && (aDouble >= 0.5);
            if(step == 1){
                accelCorr.add(new Point(seconds/1000,aDouble));
                toFroAuthenticated = authenticated? true: false;
            }else if(step == 2){
                gyroCorr.add(new Point(seconds/1000, aDouble));
                rotateAuthenticated = authenticated? true: false;
            }
            if(seconds >= 7000){
                stopScan();
            }
        }
    }



}
