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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Timer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import com.example.martin.proxauth.LineWorks.*;

@SuppressWarnings("ALL")
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private float[]gyro = new float[3];
    private float[]gyroMatrix = new float[3];
    private float[]gyroOrientation = new float[3];
    private float[]magent = new float[3];
    private float[]accel = new float[3];
    private float[]accMagOrientation = new float[3];
    private float[]fusedOrientation = new float[3];
    private float[]rotationMatrix = new float[9];

    public static final float EPSILON = 0.000000001f;
    private static final float NS2S = 1.0f / 1000000000.0f;
    private float timestamp;
    private boolean initState = true;
    public static final int TIME_CONSTANT = 30;
    public static final float FILTER_COEFFICIENT = 0.98f;
    private Timer fuseTimer = new Timer();

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

    static List<Point> sampledAccel,
                sampledRSSIAccel,
                sampledRSSIGyro,
                sampledGyro,
                accelCorr,
                gyroCorr;

    private ConcurrentHashMap<Integer, Point> rssi_accelPointMap;
    private ConcurrentHashMap<Integer, Point> rssi_gyroPointMap;
    private ConcurrentHashMap<Integer, Point> gyroPointMap;
    private ConcurrentHashMap<Integer, Point> accelPointMap;

    public static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static int step = 0;

    private static int rssiAccelSize = 0;
    private static int rssiGyroSize = 0;
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

        int i;
        for(i=0;i<3;i++){
            gyroOrientation[i] = 0.0f;
        }
        for(i=0;i<9;i++){
            gyroOrientation[i] = 0.0f;
        }
        for(i=0;i<9;i+=4){
            gyroOrientation[i] = 1.0f;
        }
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

        accelCorr = new ArrayList<>();
        gyroCorr = new ArrayList<>();

        rssi_gyroPointMap = new ConcurrentHashMap<>();
        rssi_accelPointMap = new ConcurrentHashMap<>();
        gyroPointMap = new ConcurrentHashMap<>();
        accelPointMap = new ConcurrentHashMap<>();

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
        sensorManager.registerListener(this,sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),100000);
    }

    private Point lastRSSIAccelUpdate = null;
    private Point lastRSSIGyroUpdate = null;
    private double initialRSSIAccelTime = 0;
    private double initialRSSIGyroTime = 0;
    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            double currentTime = (double)System.currentTimeMillis();

            int rssi = result.getRssi();
            if(step == 1) {
                if (lastRSSIAccelUpdate == null) {
                    lastRSSIAccelUpdate = new Point(0.0, rssi);
                    initialRSSIAccelTime = currentTime;
                } else {
                    double elapsedTime;

                    if (rssiAccelSize == 0) {
                        elapsedTime = (currentTime - initialRSSIAccelTime) / 1000;
                    } else {
                        elapsedTime = ((currentTime - initialRSSIAccelTime) / 1000) - lastRSSIAccelUpdate.getX();
                    }

                    if (elapsedTime < 0.1) {
                        return;
                    }

                    double time = elapsedTime + lastRSSIAccelUpdate.getX();
                    rssi_accelPointMap.put(++rssiAccelSize,new Point(lastRSSIAccelUpdate));
                    lastRSSIAccelUpdate = new Point(time, rssi);
                }
            }else if(step ==2){
                if(lastRSSIGyroUpdate == null){
                    lastRSSIGyroUpdate = new Point(0.0,rssi);
                    initialRSSIGyroTime = currentTime;
                }else{
                    double elapsedTime;

                    if(rssiGyroSize == 0){
                        elapsedTime = (currentTime - initialRSSIGyroTime) / 1000;
                    }else{
                        elapsedTime = ((currentTime - initialRSSIGyroTime)/1000) - lastRSSIGyroUpdate.getX();
                    }

                    if(elapsedTime < 0.1){
                        return;
                    }
                    double time = elapsedTime + lastRSSIGyroUpdate.getX();
                    rssi_gyroPointMap.put(++rssiGyroSize,new Point(lastRSSIGyroUpdate));
                    lastRSSIGyroUpdate = new Point(time, rssi);
                }
            }
            addressTextView.setText(result.getDevice().getAddress());
        }
    };

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


    private boolean toFroAuthenticated = false;
    private boolean rotateAuthenticated = false;

    private void stopScan(){
        bleScanner.stopScan(scanCallback);
        sensorManager.unregisterListener(this);
        stopTimer();
        seconds = 0l;
        processTextView.setVisibility(View.INVISIBLE);
        controlButton.setVisibility(View.VISIBLE);
        isScanning = false;
        accelSize = 0;
        rssiAccelSize = 0;
        gyroSize = 0;
        rssiGyroSize = 0;
        lastRSSIAccelUpdate = null;
        lastRSSIGyroUpdate = null;
        lastAccelUpdate = null;
        lastGyroUpdate = null;

        try {
            int i;
            String record;
            if (step == 1) {
                int accelSampleSize = sampledAccel.size();
                int accelRSSISampleSize = sampledRSSIAccel.size();

                for (i=0; i < accelRSSISampleSize; i++) {
                    record = sampledRSSIAccel.get(i).getX() + "\t" + sampledRSSIAccel.get(i).getY() + "\t" + sampledAccel.get(i).getY()+ "\n";
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
            gyroPointMap.clear();
            rssi_accelPointMap.clear();
            rssi_gyroPointMap.clear();
            accelPointMap.clear();
            accelCorr.clear();
            gyroCorr.clear();
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
                UpdateAsyncTasks updateAsyncTasks = null;
                if(step == 1){
                    expanded = !expanded;
                    motionGesture(false,true,expanded, degrees);

                    if(seconds <= 7000){
                        if(updateAsyncTasks != null){
                            updateAsyncTasks.cancel(false);
                        }
                        updateAsyncTasks = new UpdateAsyncTasks();
                        updateAsyncTasks.execute(accelSize,rssiAccelSize);
                    }else{
                        if(updateAsyncTasks != null) {
                            updateAsyncTasks.cancel(false);
                        }
                        if(accelCorr.get(accelCorr.size()-1).getY() >= 0.5){
                            toFroAuthenticated = true;
                        }
                        stopScan();
                        return;
                    }
                }else if(step == 2){
                    degrees = -degrees;
                    motionGesture(true,false,expanded, degrees);

                    if(seconds <= 7000){
                        if(updateAsyncTasks != null){
                            updateAsyncTasks.cancel(false);
                        }
                         updateAsyncTasks = new UpdateAsyncTasks();
                         updateAsyncTasks.execute(gyroSize,rssiGyroSize);
                    }else{
                        if(updateAsyncTasks != null) {
                            updateAsyncTasks.cancel(false);
                        }
                        if(gyroCorr.get(gyroCorr.size()-1).getY() >= 0.5){
                            rotateAuthenticated = true;
                        }
                        stopScan();
                        return;
                    }
                }
                if(count++ == 2){
                    count = 0;
                    process = "Authenticating";
                }
                process+=" .";
                processTextView.setText(process);
                handler.postDelayed(this,1000);
            }
        };
        handler.postDelayed(runnable,1000);
    }

    private void stopTimer(){
        handler.removeCallbacks(runnable);
        motionGesture(true,false,expanded,0.0f);
    }

    private static Point lastAccelUpdate = null;
    private static Point lastGyroUpdate = null;
    private double initialAccelTime = 0;
    private double initialGyroTime = 0;

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {

        double currentTime = (double) System.currentTimeMillis();

        if(sensorEvent.sensor.getType() == Constants.ACCType && step ==1){

            if(lastAccelUpdate == null){

                initialAccelTime = currentTime;
                lastAccelUpdate = new Point(0.0,sensorEvent.values[2]);
            }else {

                double time = (currentTime - initialAccelTime) / 1000;
                accelPointMap.put(++accelSize,new Point(lastAccelUpdate));
                Point newPoint = new Point(time, sensorEvent.values[2]);
                lastAccelUpdate = newPoint;
            }
        }
        if(sensorEvent.sensor.getType() == Constants.GYROType && step == 2){

            Log.e(MainActivity.class.getSimpleName(),"GYRO: "+sensorEvent.values[2]);

            if(lastGyroUpdate == null){

                initialGyroTime = currentTime;
                lastGyroUpdate = new Point(0.0,sensorEvent.values[2]);
            }else{

                double time = (currentTime-initialGyroTime)/1000;
                gyroPointMap.put(++gyroSize, new Point(lastGyroUpdate));
                Point newPoint = new Point(time, sensorEvent.values[2]);
                lastGyroUpdate = newPoint;
            }
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }


    private class UpdateAsyncTasks extends AsyncTask<Integer,Void,Void>{

        @Override
        protected Void doInBackground(Integer... sizes) {
            if(step == 1){
                Log.e(LOG_TAG, "Accel: "+accelPointMap+"\n RSSI: "+rssi_accelPointMap);
                Point point =  Process.process(step, sizes[0], sizes[1], accelPointMap, rssi_accelPointMap);
                accelCorr.add(point);
            }else if(step == 2){
                Point point =  Process.process(step, sizes[0], sizes[1], gyroPointMap, rssi_gyroPointMap);
                gyroCorr.add(point);
            }

            return null;
        }

    }

    private static class Process{

        public static Point process(int flag, int sizes0, int sizes1, ConcurrentHashMap<Integer, Point>... lineMaps){
           List<Line> smoothedCurve1 = LineSmoother.smoothLine(lineMaps[0], sizes0);
           List<Line> smoothedCurve2 = LineSmoother.smoothLine(lineMaps[1], sizes1);

           Log.e(MainActivity.class.getSimpleName(),"Flag: "+flag);

           int size1 = smoothedCurve1.size();
           int size2 = smoothedCurve2.size();

           double end = (int) Math.min(smoothedCurve1.get(size1-1).getPoint2().getX(), smoothedCurve2.get(size2-1).getPoint2().getX());
           List<Point> sampled1 = LineSmoother.sample(smoothedCurve1,0,0.1,end);
           List<Point> sampled2 = LineSmoother.sample(smoothedCurve2,0,0.1,end);

           sampled1 = LineSmoother.standardize(sampled1);
           sampled2 = LineSmoother.standardize(sampled2);

           double corr = LineSmoother.corrCoeff(sampled1,sampled2);

           if(flag == 1){
               sampledAccel = sampled1;
               sampledRSSIAccel = sampled2;
           }else{
               sampledGyro = sampled1;
               sampledRSSIGyro = sampled2;
           }

           return new Point(end, corr);
        }
    }

}
