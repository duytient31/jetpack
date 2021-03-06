/*
 * Copyright (C) 2019 The Android Open Source Project
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

package androidx.camera.integration.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraX;
import androidx.camera.core.CameraX.LensFacing;
import androidx.camera.core.FlashMode;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageAnalysisConfig;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureConfig;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.camera.core.UseCase;
import androidx.camera.core.VideoCapture;
import androidx.camera.core.VideoCaptureConfig;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.test.espresso.IdlingResource;
import androidx.test.espresso.idling.CountingIdlingResource;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.math.BigDecimal;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


/**
 * An activity with four use cases: (1) view finder, (2) image capture, (3) image analysis, (4)
 * video capture.
 *
 * <p>All four use cases are created with CameraX and tied to the activity's lifecycle. CameraX
 * automatically connects and disconnects the use cases from the camera in response to changes in
 * the activity's lifecycle. Therefore, the use cases function properly when the app is paused and
 * resumed and when the device is rotated. The complex interactions between the camera and these
 * lifecycle events are handled internally by CameraX.
 */
public class CameraXActivity extends AppCompatActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback, View.OnLayoutChangeListener {
    private static final String TAG = "CameraXActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 42;
    // Possible values for this intent key: "backward" or "forward".
    private static final String INTENT_EXTRA_CAMERA_DIRECTION = "camera_direction";

    private final SettableCallable<Boolean> mSettableResult = new SettableCallable<>();
    private final FutureTask<Boolean> mCompletableFuture = new FutureTask<>(mSettableResult);
    private final AtomicLong mImageAnalysisFrameCount = new AtomicLong(0);
    private final MutableLiveData<String> mImageAnalysisResult = new MutableLiveData<>();
    private VideoFileSaver mVideoFileSaver;
    /** The cameraId to use. Assume that 0 is the typical back facing camera. */
    private LensFacing mCurrentCameraLensFacing = LensFacing.BACK;

    // TODO: Move the analysis processing, capture processing to separate threads, so
    // there is smaller impact on the preview.
    private String mCurrentCameraDirection = "BACKWARD";
    private Preview mPreview;
    private ImageAnalysis mImageAnalysis;
    private ImageCapture mImageCapture;
    private VideoCapture mVideoCapture;
    private ImageCapture.CaptureMode mCaptureMode = ImageCapture.CaptureMode.MIN_LATENCY;
    private TextureView mTextureView;
    TextureViewSurfaceTextureListener mTextureViewSurfaceTextureListener =
            new TextureViewSurfaceTextureListener();

    // Espresso testing variables
    @VisibleForTesting
    CountingIdlingResource mViewIdlingResource = new CountingIdlingResource("view");
    private static final int FRAMES_UNTIL_VIEW_IS_READY = 5;
    @VisibleForTesting
    CountingIdlingResource mAnalysisIdlingResource =
            new CountingIdlingResource("analysis");
    @VisibleForTesting
    final CountingIdlingResource mImageSavedIdlingResource =
            new CountingIdlingResource("imagesaved");

    /**
     * Retrieve idling resource that waits for capture to complete (save or error).
     *
     * @return idline resource for image capture
     */
    @VisibleForTesting
    @NonNull
    public IdlingResource getImageSavedIdlingResource() {
        return mImageSavedIdlingResource;
    }

    /**
     * Creates a view finder use case.
     *
     * <p>This use case observes a {@link SurfaceTexture}. The texture is connected to a {@link
     * TextureView} to display a camera preview.
     */
    private void createPreview() {
        Button button = this.findViewById(R.id.PreviewToggle);
        button.setBackgroundColor(Color.LTGRAY);
        enablePreview();

        button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Button buttonView = (Button) view;
                        if (mPreview != null) {
                            // Remove the use case
                            buttonView.setBackgroundColor(Color.RED);
                            CameraX.unbind(mPreview);
                            mPreview = null;
                        } else {
                            // Add the use case
                            buttonView.setBackgroundColor(Color.LTGRAY);

                            CameraXActivity.this.enablePreview();
                        }
                    }
                });

        Log.i(TAG, "Got UseCase: " + mPreview);
    }

    void enablePreview() {
        PreviewConfig config =
                new PreviewConfig.Builder()
                        .setLensFacing(mCurrentCameraLensFacing)
                        .setTargetName("Preview")
                        .build();

        mPreview = new Preview(config);
        Log.d(TAG, "enablePreview");
        mPreview.setPreviewSurfaceCallback(new Preview.PreviewSurfaceCallback() {

            @NonNull
            @Override
            public ListenableFuture<Surface> createSurfaceFuture(@NonNull Size resolution,
                    int imageFormat) {
                Log.d(TAG, "Surface requested by CameraX Preview. Size: " + resolution);
                return CallbackToFutureAdapter.getFuture(
                        completer -> {
                            mTextureView.getSurfaceTexture().setDefaultBufferSize(
                                    resolution.getWidth(), resolution.getHeight());
                            mTextureViewSurfaceTextureListener.setCompleter(completer,
                                    new Surface(mTextureView.getSurfaceTexture()));
                            return "GetTextureViewSurface";
                        });
            }

            @Override
            public void onSafeToRelease(@NonNull ListenableFuture<Surface> surfaceFuture) {
                Log.d(TAG, "Release Surface.");
                try {
                    surfaceFuture.get().release();
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Failed to release Surface", e);
                }
            }
        });

        for (int i = 0; i < FRAMES_UNTIL_VIEW_IS_READY; i++) {
            mViewIdlingResource.increment();
        }

        if (!bindToLifecycleSafely(mPreview, R.id.PreviewToggle)) {
            mPreview = null;
            return;
        }
    }

    void transformPreview() {
        String cameraId = null;
        PreviewConfig config = (PreviewConfig) mPreview.getUseCaseConfig();
        LensFacing previewLensFacing = config.getLensFacing(/*valueIfMissing=*/ null);
        if (previewLensFacing != mCurrentCameraLensFacing) {
            throw new IllegalStateException(
                    "Invalid preview lens facing: "
                            + previewLensFacing
                            + " Should be: "
                            + mCurrentCameraLensFacing);
        }
        try {
            cameraId = CameraX.getCameraWithCameraDeviceConfig(config);
        } catch (CameraInfoUnavailableException e) {
            throw new IllegalArgumentException(
                    "Unable to get camera id for the camera device config "
                            + config.getLensFacing(), e);
        }
        Size srcResolution = mPreview.getAttachedSurfaceResolution(cameraId);

        if (srcResolution.getWidth() == 0 || srcResolution.getHeight() == 0) {
            return;
        }

        if (mTextureView.getWidth() == 0 || mTextureView.getHeight() == 0) {
            return;
        }

        Matrix matrix = new Matrix();

        int left = mTextureView.getLeft();
        int right = mTextureView.getRight();
        int top = mTextureView.getTop();
        int bottom = mTextureView.getBottom();

        // Compute the preview ui size based on the available width, height, and ui orientation.
        int viewWidth = (right - left);
        int viewHeight = (bottom - top);

        int displayRotation = getDisplayRotation();
        Size scaled =
                calculatePreviewViewDimens(
                        srcResolution, viewWidth, viewHeight, displayRotation);

        // Compute the center of the view.
        int centerX = viewWidth / 2;
        int centerY = viewHeight / 2;

        // Do corresponding rotation to correct the preview direction
        matrix.postRotate(-getDisplayRotation(), centerX, centerY);

        // Compute the scale value for center crop mode
        float xScale = scaled.getWidth() / (float) viewWidth;
        float yScale = scaled.getHeight() / (float) viewHeight;

        if (getDisplayRotation() == 90 || getDisplayRotation() == 270) {
            xScale = scaled.getWidth() / (float) viewHeight;
            yScale = scaled.getHeight() / (float) viewWidth;
        }

        // Only two digits after the decimal point are valid for postScale. Need to get ceiling of
        // two
        // digits floating value to do the scale operation. Otherwise, the result may be scaled not
        // large enough and will have some blank lines on the screen.
        xScale = new BigDecimal(xScale).setScale(2, BigDecimal.ROUND_CEILING).floatValue();
        yScale = new BigDecimal(yScale).setScale(2, BigDecimal.ROUND_CEILING).floatValue();

        // Do corresponding scale to resolve the deformation problem
        matrix.postScale(xScale, yScale, centerX, centerY);

        // Compute the new left/top positions to do translate
        int layoutL = centerX - (scaled.getWidth() / 2);
        int layoutT = centerY - (scaled.getHeight() / 2);

        mTextureView.setTransform(matrix);
    }

    /** @return One of 0, 90, 180, 270. */
    private int getDisplayRotation() {
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();

        switch (displayRotation) {
            case Surface.ROTATION_0:
                displayRotation = 0;
                break;
            case Surface.ROTATION_90:
                displayRotation = 90;
                break;
            case Surface.ROTATION_180:
                displayRotation = 180;
                break;
            case Surface.ROTATION_270:
                displayRotation = 270;
                break;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported display rotation: " + displayRotation);
        }

        return displayRotation;
    }

    private Size calculatePreviewViewDimens(
            Size srcSize, int parentWidth, int parentHeight, int displayRotation) {
        int inWidth = srcSize.getWidth();
        int inHeight = srcSize.getHeight();
        if (displayRotation == 0 || displayRotation == 180) {
            // Need to reverse the width and height since we're in landscape orientation.
            inWidth = srcSize.getHeight();
            inHeight = srcSize.getWidth();
        }

        int outWidth = parentWidth;
        int outHeight = parentHeight;
        if (inWidth != 0 && inHeight != 0) {
            float vfRatio = inWidth / (float) inHeight;
            float parentRatio = parentWidth / (float) parentHeight;

            // Match shortest sides together.
            if (vfRatio < parentRatio) {
                outWidth = parentWidth;
                outHeight = Math.round(parentWidth / vfRatio);
            } else {
                outWidth = Math.round(parentHeight * vfRatio);
                outHeight = parentHeight;
            }
        }

        return new Size(outWidth, outHeight);
    }

    /**
     * Creates an image analysis use case.
     *
     * <p>This use case observes a stream of analysis results computed from the frames.
     */
    private void createImageAnalysis() {
        Button button = this.findViewById(R.id.AnalysisToggle);
        button.setBackgroundColor(Color.LTGRAY);
        enableImageAnalysis();

        button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Button buttonView = (Button) view;
                        if (mImageAnalysis != null) {
                            // Remove the use case
                            buttonView.setBackgroundColor(Color.RED);
                            CameraX.unbind(mImageAnalysis);
                            mImageAnalysis = null;
                        } else {
                            // Add the use case
                            buttonView.setBackgroundColor(Color.LTGRAY);
                            CameraXActivity.this.enableImageAnalysis();
                        }
                    }
                });

        Log.i(TAG, "Got UseCase: " + mImageAnalysis);
    }

    void enableImageAnalysis() {
        ImageAnalysisConfig config =
                new ImageAnalysisConfig.Builder()
                        .setLensFacing(mCurrentCameraLensFacing)
                        .setTargetName("ImageAnalysis")
                        .build();

        mImageAnalysis = new ImageAnalysis(config);
        TextView textView = this.findViewById(R.id.textView);
        mAnalysisIdlingResource.increment();

        if (!bindToLifecycleSafely(mImageAnalysis, R.id.AnalysisToggle)) {
            mImageAnalysis = null;
            return;
        }

        mImageAnalysis.setAnalyzer(
                CameraXExecutors.mainThreadExecutor(),
                new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy image, int rotationDegrees) {
                        // Since we set the callback handler to a main thread handler, we can call
                        // setValue()
                        // here. If we weren't on the main thread, we would have to call postValue()
                        // instead.
                        mImageAnalysisResult.setValue(Long.toString(image.getTimestamp()));

                        if (!mAnalysisIdlingResource.isIdleNow()) {
                            mAnalysisIdlingResource.decrement();
                        }
                    }
                });
        mImageAnalysisResult.observe(
                this,
                new Observer<String>() {
                    @Override
                    public void onChanged(String text) {
                        if (mImageAnalysisFrameCount.getAndIncrement() % 30 == 0) {
                            textView.setText(
                                    "ImgCount: " + mImageAnalysisFrameCount.get() + " @ts: "
                                            + text);
                        }
                    }
                });
    }

    /**
     * Creates an image capture use case.
     *
     * <p>This use case takes a picture and saves it to a file, whenever the user clicks a button.
     */
    private void createImageCapture() {

        Button button = this.findViewById(R.id.PhotoToggle);
        button.setBackgroundColor(Color.LTGRAY);
        enableImageCapture();

        button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Button buttonView = (Button) view;
                        if (mImageCapture != null) {
                            // Remove the use case
                            buttonView.setBackgroundColor(Color.RED);
                            CameraXActivity.this.disableImageCapture();
                        } else {
                            // Add the use case
                            buttonView.setBackgroundColor(Color.LTGRAY);
                            CameraXActivity.this.enableImageCapture();
                        }
                    }
                });

        Log.i(TAG, "Got UseCase: " + mImageCapture);
    }

    void enableImageCapture() {
        ImageCaptureConfig config =
                new ImageCaptureConfig.Builder()
                        .setLensFacing(mCurrentCameraLensFacing)
                        .setCaptureMode(mCaptureMode)
                        .setTargetName("ImageCapture")
                        .build();

        mImageCapture = new ImageCapture(config);

        if (!bindToLifecycleSafely(mImageCapture, R.id.PhotoToggle)) {
            Button button = this.findViewById(R.id.Picture);
            button.setOnClickListener(null);
            mImageCapture = null;
            return;
        }

        Button button = this.findViewById(R.id.Picture);
        final Format formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");
        final File dir = this.getExternalFilesDir(null);
        button.setOnClickListener(
                new View.OnClickListener() {
                    long mStartCaptureTime = 0;

                    @Override
                    public void onClick(View view) {
                        mImageSavedIdlingResource.increment();

                        mStartCaptureTime = SystemClock.elapsedRealtime();
                        mImageCapture.takePicture(
                                new File(
                                        dir,
                                        formatter.format(Calendar.getInstance().getTime())
                                                + ".jpg"),
                                CameraXExecutors.mainThreadExecutor(),
                                new ImageCapture.OnImageSavedCallback() {
                                    @Override
                                    public void onImageSaved(@NonNull File file) {
                                        Log.d(TAG, "Saved image to " + file);
                                        try {
                                            mImageSavedIdlingResource.decrement();
                                        } catch (IllegalStateException e) {
                                            Log.e(TAG, "Error: unexpected onImageSaved "
                                                    + "callback received. Continuing.");
                                        }

                                        long duration =
                                                SystemClock.elapsedRealtime() - mStartCaptureTime;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                Toast.makeText(CameraXActivity.this,
                                                        "Image captured in " + duration + " ms",
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }

                                    @Override
                                    public void onError(
                                            @NonNull ImageCapture.ImageCaptureError error,
                                            @NonNull String message,
                                            Throwable cause) {
                                        Log.e(TAG, "Failed to save image.", cause);
                                        try {
                                            mImageSavedIdlingResource.decrement();
                                        } catch (IllegalStateException e) {
                                            Log.e(TAG, "Error: unexpected onImageSaved "
                                                    + "callback received. Continuing.");
                                        }
                                    }
                                });
                    }
                });

        refreshFlashButtonIcon();


        Button btnCaptureQuality = this.findViewById(R.id.capture_quality);
        btnCaptureQuality.setVisibility(View.VISIBLE);
        btnCaptureQuality.setText(
                mCaptureMode == ImageCapture.CaptureMode.MAX_QUALITY ? "MAX" : "MIN");
        btnCaptureQuality.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCaptureMode = (mCaptureMode == ImageCapture.CaptureMode.MAX_QUALITY
                        ? ImageCapture.CaptureMode.MIN_LATENCY
                        : ImageCapture.CaptureMode.MAX_QUALITY);
                rebindUseCases();
            }
        });

    }

    void disableImageCapture() {
        CameraX.unbind(mImageCapture);

        mImageCapture = null;
        Button button = this.findViewById(R.id.Picture);
        button.setOnClickListener(null);

        Button btnCaptureQuality = this.findViewById(R.id.capture_quality);
        btnCaptureQuality.setVisibility(View.GONE);

        refreshFlashButtonIcon();
    }

    private void refreshFlashButtonIcon() {
        ImageButton flashToggle = findViewById(R.id.flash_toggle);
        if (mImageCapture != null) {

            try {
                CameraInfo cameraInfo = CameraX.getCameraInfo(mCurrentCameraLensFacing);
                LiveData<Boolean> isFlashAvailable = cameraInfo.isFlashAvailable();
                flashToggle.setVisibility(
                        isFlashAvailable.getValue() ? View.VISIBLE : View.INVISIBLE);
            } catch (CameraInfoUnavailableException e) {
                Log.w(TAG, "Cannot get flash available information", e);
                flashToggle.setVisibility(View.INVISIBLE);
            }

            flashToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FlashMode flashMode = mImageCapture.getFlashMode();
                    if (flashMode == FlashMode.ON) {
                        mImageCapture.setFlashMode(FlashMode.OFF);
                    } else if (flashMode == FlashMode.OFF) {
                        mImageCapture.setFlashMode(FlashMode.AUTO);
                    } else if (flashMode == FlashMode.AUTO) {
                        mImageCapture.setFlashMode(FlashMode.ON);
                    }
                    refreshFlashButtonIcon();
                }
            });
            FlashMode flashMode = mImageCapture.getFlashMode();
            switch (flashMode) {
                case ON:
                    flashToggle.setImageResource(R.drawable.ic_flash_on);
                    break;
                case OFF:
                    flashToggle.setImageResource(R.drawable.ic_flash_off);
                    break;
                case AUTO:
                    flashToggle.setImageResource(R.drawable.ic_flash_auto);
                    break;
            }

        } else {
            flashToggle.setVisibility(View.GONE);
            flashToggle.setOnClickListener(null);
        }
    }

    /**
     * Creates a video capture use case.
     *
     * <p>This use case records a video segment and saves it to a file, in response to user button
     * clicks.
     */
    private void createVideoCapture() {
        Button button = this.findViewById(R.id.VideoToggle);
        button.setBackgroundColor(Color.LTGRAY);
        enableVideoCapture();

        mVideoFileSaver = new VideoFileSaver();
        mVideoFileSaver.setRootDirectory(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));

        button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Button buttonView = (Button) view;
                        if (mVideoCapture != null) {
                            // Remove the use case
                            buttonView.setBackgroundColor(Color.RED);
                            CameraXActivity.this.disableVideoCapture();
                        } else {
                            // Add the use case
                            buttonView.setBackgroundColor(Color.LTGRAY);
                            CameraXActivity.this.enableVideoCapture();
                        }
                    }
                });

        Log.i(TAG, "Got UseCase: " + mVideoCapture);
    }

    void enableVideoCapture() {
        VideoCaptureConfig config =
                new VideoCaptureConfig.Builder()
                        .setLensFacing(mCurrentCameraLensFacing)
                        .setTargetName("VideoCapture")
                        .build();

        mVideoCapture = new VideoCapture(config);

        if (!bindToLifecycleSafely(mVideoCapture, R.id.VideoToggle)) {
            Button button = this.findViewById(R.id.Video);
            button.setOnClickListener(null);
            mVideoCapture = null;
            return;
        }

        Button button = this.findViewById(R.id.Video);
        button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Button buttonView = (Button) view;
                        String text = button.getText().toString();
                        if (text.equals("Record") && !mVideoFileSaver.isSaving()) {
                            mVideoCapture.startRecording(
                                    mVideoFileSaver.getNewVideoFile(),
                                    CameraXExecutors.mainThreadExecutor(), mVideoFileSaver);
                            mVideoFileSaver.setSaving();
                            buttonView.setText("Stop");
                        } else if (text.equals("Stop") && mVideoFileSaver.isSaving()) {
                            buttonView.setText("Record");
                            mVideoCapture.stopRecording();
                        } else if (text.equals("Record") && mVideoFileSaver.isSaving()) {
                            buttonView.setText("Stop");
                            mVideoFileSaver.setSaving();
                        } else if (text.equals("Stop") && !mVideoFileSaver.isSaving()) {
                            buttonView.setText("Record");
                        }
                    }
                });
    }

    void disableVideoCapture() {
        Button button = this.findViewById(R.id.Video);
        button.setOnClickListener(null);
        CameraX.unbind(mVideoCapture);

        mVideoCapture = null;
    }

    /** Creates all the use cases. */
    private void createUseCases() {
        createImageCapture();
        createPreview();
        createImageAnalysis();
        createVideoCapture();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_xmain);
        mTextureView = findViewById(R.id.textureView);
        // Set SurfaceTextureListener in onCreate() to make sure onSurfaceTextureAvailable() is
        // triggered.
        mTextureView.setSurfaceTextureListener(mTextureViewSurfaceTextureListener);

        StrictMode.VmPolicy policy =
                new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build();
        StrictMode.setVmPolicy(policy);

        // Get params from adb extra string
        Bundle bundle = this.getIntent().getExtras();
        if (bundle != null) {
            String newCameraDirection = bundle.getString(INTENT_EXTRA_CAMERA_DIRECTION);
            if (newCameraDirection != null) {
                mCurrentCameraDirection = newCameraDirection;
            }
        }

        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        CameraXActivity.this.setupCamera();
                    }
                })
                .start();
        setupPermissions();
    }

    private void setupCamera() {
        try {
            // Wait for permissions before proceeding.
            if (!mCompletableFuture.get()) {
                Log.d(TAG, "Permissions denied.");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception occurred getting permission future: " + e);
        }

        Log.d(TAG, "Camera direction: " + mCurrentCameraDirection);
        if (mCurrentCameraDirection.equalsIgnoreCase("BACKWARD")) {
            mCurrentCameraLensFacing = LensFacing.BACK;
        } else if (mCurrentCameraDirection.equalsIgnoreCase("FORWARD")) {
            mCurrentCameraLensFacing = LensFacing.FRONT;
        } else {
            throw new RuntimeException("Invalid camera direction: " + mCurrentCameraDirection);
        }
        Log.d(TAG, "Using camera lens facing: " + mCurrentCameraLensFacing);

        // Run this on the UI thread to manipulate the Textures & Views.
        CameraXActivity.this.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        CameraXActivity.this.createUseCases();

                        ImageButton directionToggle = findViewById(R.id.direction_toggle);
                        directionToggle.setVisibility(View.VISIBLE);
                        directionToggle.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mCurrentCameraLensFacing == LensFacing.BACK) {
                                    mCurrentCameraLensFacing = LensFacing.FRONT;
                                } else if (mCurrentCameraLensFacing == LensFacing.FRONT) {
                                    mCurrentCameraLensFacing = LensFacing.BACK;
                                }

                                Log.d(TAG, "Change camera direction: " + mCurrentCameraLensFacing);
                                rebindUseCases();

                            }
                        });

                        ImageButton torchToggle = findViewById(R.id.torch_toggle);
                        torchToggle.setVisibility(View.VISIBLE);
                        torchToggle.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mPreview != null) {
                                    boolean toggledState = !mPreview.isTorchOn();
                                    Log.d(TAG, "Set camera torch: " + toggledState);
                                    mPreview.enableTorch(toggledState);
                                }
                            }
                        });
                    }
                });
    }

    private void rebindUseCases() {
        // Rebind all use cases.
        CameraX.unbindAll();
        if (mImageCapture != null) {
            enableImageCapture();
        }
        if (mPreview != null) {
            enablePreview();
        }
        if (mImageAnalysis != null) {
            enableImageAnalysis();
        }
        if (mVideoCapture != null) {
            enableVideoCapture();
        }
    }

    private void setupPermissions() {
        if (!allPermissionsGranted()) {
            makePermissionRequest();
        } else {
            mSettableResult.set(true);
            mCompletableFuture.run();
        }
    }

    private void makePermissionRequest() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSIONS_REQUEST_CODE);
    }

    /** Returns true if all the necessary permissions have been granted already. */
    private boolean allPermissionsGranted() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Tries to acquire all the necessary permissions through a dialog. */
    private String[] getRequiredPermissions() {
        PackageInfo info;
        try {
            info =
                    getPackageManager()
                            .getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException exception) {
            Log.e(TAG, "Failed to obtain all required permissions.", exception);
            return new String[0];
        }
        String[] permissions = info.requestedPermissions;
        if (permissions != null && permissions.length > 0) {
            return permissions;
        } else {
            return new String[0];
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permissions Granted.");
                    mSettableResult.set(true);
                    mCompletableFuture.run();
                } else {
                    Log.d(TAG, "Permissions Denied.");
                    mSettableResult.set(false);
                    mCompletableFuture.run();
                }
                return;
            }
            default:
                // No-op
        }
    }

    private boolean bindToLifecycleSafely(UseCase useCase, int buttonViewId) {
        try {
            CameraX.bindToLifecycle(this, useCase);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, e.getMessage());
            Toast.makeText(getApplicationContext(), "Bind too many use cases.", Toast.LENGTH_SHORT)
                    .show();
            Button button = this.findViewById(buttonViewId);
            button.setBackgroundColor(Color.RED);
            return false;
        }

        return true;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        transformPreview();
    }

    /** A {@link Callable} whose return value can be set. */
    private static final class SettableCallable<V> implements Callable<V> {
        private final AtomicReference<V> mValue = new AtomicReference<>();

        public void set(V value) {
            mValue.set(value);
        }

        @Override
        public V call() {
            return mValue.get();
        }
    }

    Preview getPreview() {
        return mPreview;
    }

    ImageAnalysis getImageAnalysis() {
        return mImageAnalysis;
    }

    ImageCapture getImageCapture() {
        return mImageCapture;
    }

    VideoCapture getVideoCapture() {
        return mVideoCapture;
    }

    /**
     * Class to resolve the race condition where the Surface future should
     * only be completed when both the SurfaceTexture and the Preview are ready.
     */
    class TextureViewSurfaceTextureListener implements SurfaceTextureListener {

        @GuardedBy("this")
        private CallbackToFutureAdapter.Completer<Surface> mCompleter;
        @GuardedBy("this")
        private Surface mSurface;

        private boolean mIsSurfaceTextureAvailable = false;

        /**
         * Called when {@link Preview} is ready for Surface.
         */
        synchronized void setCompleter(CallbackToFutureAdapter.Completer<Surface> completer,
                Surface surface) {
            if (mCompleter != null) {
                mCompleter.setCancelled();
            }
            mCompleter = completer;
            mSurface = surface;
            tryComplete();
        }

        /**
         * Completes the Surface future if both {@link Preview} and {@link TextureView} are ready.
         */
        private synchronized void tryComplete() {
            if (mCompleter != null && mSurface != null && mIsSurfaceTextureAvailable) {
                mCompleter.set(mSurface);
                mCompleter = null;
                mSurface = null;
                transformPreview();
            }
        }

        /**
         * Called when SurfaceTexture is available.
         */
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                int width,
                int height) {
            mIsSurfaceTextureAvailable = true;
            tryComplete();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width,
                int height) {
            // No need to handle here since this app doesn't change the View size.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            // Wait until surface texture receives enough updates.
            if (!mViewIdlingResource.isIdleNow()) {
                mViewIdlingResource.decrement();
            }
        }
    }
}
