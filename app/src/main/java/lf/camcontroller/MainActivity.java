// DO NOT EDIT >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
package lf.camcontroller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    // USB communication variables
    private static final String ACTION_USB_PERMISSION = "finalusb.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbSerialDevice usbSerialDevice;
    private boolean usbBtnState = false;
    private boolean isUsbPortConnected = false;
    //Defining a Callback which triggers whenever data is read.
    private final UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] arg0) {
            final byte[] arg = arg0;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String data;
                    data = new String(arg, StandardCharsets.UTF_8);
                    Log("Recv: " + data);
                }
            }).start();
        }
    };
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log("usbDevice attached");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                try {
                    if (isUsbPortConnected) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                usbSerialDevice.close();
                                isUsbPortConnected = false;
                            }
                        });
                    }
                    Log("Device removed.");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            usbBtnState = false;
                            disconnectUsb();
                            menu.findItem(R.id.usbBtn).setTitle("USB Start");
                            Log("Stopped USB communication");
                        }
                    });
                } catch (Exception ex) {
                    Toast.makeText(getApplicationContext(), ex.toString(), Toast.LENGTH_LONG).show();
                }
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    try {
                        if (usbDevice != null) {
                            if (usbDevice.getVendorId() == 1027) {
                                UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(usbDevice);
                                usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbDeviceConnection);
                                if (usbSerialDevice != null) {
                                    if (usbSerialDevice.open()) {                                       //Set Serial Connection Parameters.
                                        usbSerialDevice.setBaudRate(9600);
                                        usbSerialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
                                        usbSerialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
                                        usbSerialDevice.setParity(UsbSerialInterface.PARITY_NONE);
                                        usbSerialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                                        usbSerialDevice.read(mCallback);
                                        isUsbPortConnected = true;
                                        Log("Connected to Port");
                                    } else {
                                        Log("Could not open port.");
                                    }
                                } else {
                                    Log("Port is null.");
                                }
                            } else Log("Device not recognized as SP-duino.");
                        } else {
                            Log("Device is null.");
                        }
                        if (!isUsbPortConnected) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    usbBtnState = false;
                                    menu.findItem(R.id.usbBtn).setTitle("USB Start");
                                }
                            });
                        }
                    } catch (Exception e) {
                        final Exception ex = e;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), ex.toString(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } else Log("Permission not granted.");
            }
        }
    };

    // OpenCV variables
    private Mat mRgba;
    private CameraBridgeViewBase opencvCamView;
    private BaseLoaderCallback baseLoaderCallback;

    // GUI and other variables
    private static final String TAG = "MainActivity";
    private Handler mainHandler;
    private TextView logTv;
    private Menu menu;
    private boolean isFrontCam = false;
    private boolean isRoboOn = false;
    private char prevCmd = '\0';

    // Initializations and UI
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler();

        Toolbar toolBar = findViewById(R.id.toolBar);
        logTv = findViewById(R.id.logView);
        logTv.setMovementMethod(new ScrollingMovementMethod());
        setSupportActionBar(toolBar);
        opencvCamView = findViewById(R.id.camView);
        opencvCamView.setCameraIndex(0);
        opencvCamView.setVisibility(SurfaceView.VISIBLE);
        opencvCamView.setCvCameraViewListener(this);

        baseLoaderCallback = new BaseLoaderCallback(this) {
            @Override
            public void onManagerConnected(int status) {
                if (status == BaseLoaderCallback.SUCCESS) {
                    Log.wtf(TAG, "OpenCV loaded successfully");
                    opencvCamView.enableView();
                } else {
                    super.onManagerConnected(status);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_items, menu);
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.toggleCamera:
                if (isFrontCam) {
                    swapCamera();
                    item.setTitle("Front Camera");
                    isFrontCam = false;
                    Log("Back Camera");
                } else {
                    swapCamera();
                    item.setTitle("Back Camera");
                    isFrontCam = true;
                    Log("Front Camera");
                }
                return true;
            case R.id.echo:
                sendCommand('M');
                return true;
            case R.id.robotBtn:
                if (isRoboOn) {
                    item.setTitle("ImageProc Start");
                    isRoboOn = false;
                    Log("ImageProc stopped.");
                } else {
                    item.setTitle("ImageProc Stop");
                    isRoboOn = true;
                    Log("ImageProc started.");
                }
                return true;
            case R.id.usbBtn:
                if (usbBtnState) {
                    usbBtnState = false;
                    disconnectUsb();
                    item.setTitle("USB Start");
                    Log("Stopped USB communication");
                } else {
                    usbBtnState = true;
                    try {
                        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
                        if (!usbDevices.isEmpty()) {
                            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                                usbDevice = entry.getValue();
                                Log("Device:" + usbDevice.getProductName());
                                PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
                                usbManager.requestPermission(usbDevice, pi);
                                Log("Requested USB communication");
                            }
                        } else {
                            Log("USB Devices list is empty.");
                            usbBtnState = false;
                        }
                    } catch (Exception ex) {
                        Log(ex.toString());
                        usbBtnState = false;
                    } finally {
                        if (usbBtnState) item.setTitle("USB Stop");
                    }
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void Log(String text) {
        final String ftext = text + "\n";
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                logTv.append(ftext);
            }
        });
    }

    // Camera and image processing
    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
    }

    @Override
    public void onCameraViewStopped() {
        mRgba.release();
        Log.wtf(TAG, "CameraView Stopped");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (opencvCamView != null) {
            opencvCamView.disableView();
            Log.wtf(TAG, "Camera Paused");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.wtf(TAG, "OpenCVLoader.initdebug() returned false");
            Toast.makeText(getApplicationContext(), "Prob 00", Toast.LENGTH_SHORT).show();
        } else {
            baseLoaderCallback.onManagerConnected(BaseLoaderCallback.SUCCESS);
            Log.wtf(TAG, "Camera Resumed");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (opencvCamView != null) {
            opencvCamView.disableView();
            Log.w(TAG, "Camera Turned OFF");
        }
        disconnectUsb();
    }

    private void swapCamera() {
        opencvCamView.disableView();
        opencvCamView.setCameraIndex(isFrontCam ? 0 : 1);
        opencvCamView.enableView();
    }

    // USB communication

    private void disconnectUsb() {
        if (isUsbPortConnected) {
            usbSerialDevice.close();
            isUsbPortConnected = false;
        }
    }

    private void sendCommand(char command) {
        if (prevCmd != command && isUsbPortConnected) {
            SendingThread s = new SendingThread(command);
            new Thread(s).start();
            prevCmd = command;
        }
    }

    private class SendingThread implements Runnable {
        byte[] msg = new byte[1];

        SendingThread(char cmd) {
            this.msg[0] = (byte) cmd;
        }

        @Override
        public void run() {
            usbSerialDevice.write(msg);
        }
    }
// <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< DO NOT EDIT

// MODIFY ACCORDING TO YOUR NEED >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
    private static final Scalar GREEN_LOWER = new Scalar(29, 86, 6);
    private static final Scalar GREEN_UPPER = new Scalar(70, 255, 255);
    private static final Scalar RED_LOWER0 = new Scalar(0, 120, 70);
    private static final Scalar RED_UPPER0 = new Scalar(10, 255, 255);
    private static final Scalar RED_LOWER1 = new Scalar(170, 120, 70);
    private static final Scalar RED_UPPER1 = new Scalar(180, 255, 255);
    private static final Scalar YELLOW_LOWER = new Scalar(85, 50, 50);
    private static final Scalar YELLOW_UPPER = new Scalar(110, 255, 255);
    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();                                          // mRgba: frame in RGBA color space
        Mat greenMask = null, yellowMask = null, redMask = null, redMaskTemp = null, hsvBlur = null;
        double[] maxContourArea = {0, 0, 0};                           // 0 -> max area of green contour; 1 -> max area of yellow contour
        Point[] encloseCenter = new Point[3];                           // 0 -> Center of green; 1 -> Center of yellow
        encloseCenter[0] = new Point(0,0);
        encloseCenter[1] = new Point(0,0);
        encloseCenter[2] = new Point(0,0);
        if (isRoboOn) {
            greenMask = new Mat(mRgba.size(), CvType.CV_8UC4);              // creates a matrix for green-masked image
            yellowMask = new Mat(mRgba.size(), CvType.CV_8UC4);              // creates a matrix for blue-masked image
            redMask = new Mat(mRgba.size(), CvType.CV_8UC4);
            redMaskTemp = new Mat(mRgba.size(), CvType.CV_8UC4);
            hsvBlur = new Mat(mRgba.size(), CvType.CV_8UC4);
            List<MatOfPoint> contours = new ArrayList<>();                  // stores the list of all the contours
            Mat interHi = new Mat();                                        // another extra matrix for manipulation
            float[] enclosingRadius = new float[1];                        // variable to store object's enclosing circle's radius
            Point tempEncloseCenter = new Point();                           // variable to store object's enclosing circle's center

            Imgproc.GaussianBlur(mRgba, hsvBlur, new Size(5, 5), 0);        // Blur the image
            Imgproc.cvtColor(hsvBlur, hsvBlur, Imgproc.COLOR_BGR2HSV);                           //  Convert the color space to HSV
            Core.inRange(hsvBlur, GREEN_LOWER, GREEN_UPPER, greenMask);                          // Create green-masked image
            Core.inRange(hsvBlur, YELLOW_LOWER, YELLOW_UPPER, yellowMask);                          // Create green-masked image
            Core.inRange(hsvBlur, RED_LOWER0, RED_UPPER0, redMaskTemp);
            Core.inRange(hsvBlur, RED_LOWER1, RED_UPPER1, redMask);
            Core.add(redMask,redMaskTemp,redMask);

            redMaskTemp.release();

            Imgproc.findContours(greenMask, contours, interHi, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            if (contours.size() > 0) {
                int maxValIdx = 0;
                // loop to find the contour with maximum area.
                for (int contourIdx = 0; contourIdx < contours.size(); contourIdx++) {
                    double contourArea = Imgproc.contourArea(contours.get(contourIdx));
                    if (maxContourArea[0] < contourArea) {
                        maxContourArea[0] = contourArea;
                        maxValIdx = contourIdx;
                    }
                }
                Imgproc.minEnclosingCircle(new MatOfPoint2f(contours.get(maxValIdx).toArray()), tempEncloseCenter, enclosingRadius);
                if (enclosingRadius[0] > 100) encloseCenter[0] = tempEncloseCenter.clone();
            }
            contours.clear();
            Imgproc.findContours(yellowMask, contours, interHi, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            if (contours.size() > 0){
                int maxValIdx = 0;
                // loop to find the contour with maximum area.
                for (int contourIdx = 0; contourIdx < contours.size(); contourIdx++) {
                    double contourArea = Imgproc.contourArea(contours.get(contourIdx));
                    if (maxContourArea[1] < contourArea) {
                        maxContourArea[1] = contourArea;
                        maxValIdx = contourIdx;
                    }
                }
                Imgproc.minEnclosingCircle(new MatOfPoint2f(contours.get(maxValIdx).toArray()), tempEncloseCenter, enclosingRadius);
                if (enclosingRadius[0] > 100) encloseCenter[1] = tempEncloseCenter.clone();
            }
            contours.clear();
            Imgproc.findContours(redMask, contours, interHi, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            if (contours.size() > 0){
                int maxValIdx = 0;
                // loop to find the contour with maximum area.
                for (int contourIdx = 0; contourIdx < contours.size(); contourIdx++) {
                    double contourArea = Imgproc.contourArea(contours.get(contourIdx));
                    if (maxContourArea[2] < contourArea) {
                        maxContourArea[2] = contourArea;
                        maxValIdx = contourIdx;
                    }
                }
                Imgproc.minEnclosingCircle(new MatOfPoint2f(contours.get(maxValIdx).toArray()), tempEncloseCenter, enclosingRadius);
                if (enclosingRadius[0] > 100) encloseCenter[2] = tempEncloseCenter.clone();
            }

            tempEncloseCenter.x = 0;
            tempEncloseCenter.y = 0;
            if((maxContourArea[0]>maxContourArea[1])&&(maxContourArea[0]>maxContourArea[2])&&!(encloseCenter[0].equals(tempEncloseCenter.clone()))) Log("GREEN");
            else if((maxContourArea[1]>maxContourArea[0])&&(maxContourArea[1]>maxContourArea[2])&&!(encloseCenter[1].equals(tempEncloseCenter.clone()))) Log("YELLOW");
            else if(!(encloseCenter[2].equals(tempEncloseCenter.clone()))) Log("RED");
            else Log("Nothing");

            //inter.release();
            interHi.release();
        }
        //Imgproc.line(mRgba, new Point(frameCenter.x - SENSITIVITY, 0), new Point(frameCenter.x - SENSITIVITY, 720), new Scalar(0, 0, 0), 7);
        //Imgproc.line(mRgba, new Point(frameCenter.x + SENSITIVITY, 0), new Point(frameCenter.x + SENSITIVITY, 720), new Scalar(0, 0, 0), 7);
        return redMask;
    }
}