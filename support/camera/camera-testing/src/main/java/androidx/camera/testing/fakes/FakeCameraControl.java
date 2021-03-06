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

package androidx.camera.testing.fakes;

import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraCaptureCallback;
import androidx.camera.core.CameraCaptureFailure;
import androidx.camera.core.CameraCaptureResult;
import androidx.camera.core.CameraControlInternal;
import androidx.camera.core.CaptureConfig;
import androidx.camera.core.FlashMode;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.SessionConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake implementation for the CameraControlInternal interface which is capable of notifying
 * submitted requests onCaptureCancelled/onCaptureCompleted/onCaptureFailed.
 */
public final class FakeCameraControl implements CameraControlInternal {
    private static final String TAG = "FakeCameraControl";
    private final ControlUpdateCallback mControlUpdateCallback;
    private final SessionConfig.Builder mSessionConfigBuilder = new SessionConfig.Builder();
    private boolean mIsTorchOn = false;
    private FlashMode mFlashMode = FlashMode.OFF;
    private ArrayList<CaptureConfig> mSubmittedCaptureRequests = new ArrayList<>();
    private OnNewCaptureRequestListener mOnNewCaptureRequestListener;

    public FakeCameraControl(@NonNull ControlUpdateCallback controlUpdateCallback) {
        mControlUpdateCallback = controlUpdateCallback;
        updateSessionConfig();
    }

    /** Notifies all submitted requests onCaptureCancelled */
    public void notifyAllRequestOnCaptureCancelled() {
        for (CaptureConfig captureConfig : mSubmittedCaptureRequests) {
            for (CameraCaptureCallback cameraCaptureCallback :
                    captureConfig.getCameraCaptureCallbacks()) {
                cameraCaptureCallback.onCaptureCancelled();
            }
        }
        mSubmittedCaptureRequests.clear();
    }

    /** Notifies all submitted requests onCaptureFailed */
    public void notifyAllRequestsOnCaptureFailed() {
        for (CaptureConfig captureConfig : mSubmittedCaptureRequests) {
            for (CameraCaptureCallback cameraCaptureCallback :
                    captureConfig.getCameraCaptureCallbacks()) {
                cameraCaptureCallback.onCaptureFailed(new CameraCaptureFailure(
                        CameraCaptureFailure.Reason.ERROR));
            }
        }
        mSubmittedCaptureRequests.clear();
    }

    /** Notifies all submitted requests onCaptureCompleted */
    public void notifyAllRequestsOnCaptureCompleted(CameraCaptureResult result) {
        for (CaptureConfig captureConfig : mSubmittedCaptureRequests) {
            for (CameraCaptureCallback cameraCaptureCallback :
                    captureConfig.getCameraCaptureCallbacks()) {
                cameraCaptureCallback.onCaptureCompleted(result);
            }
        }
        mSubmittedCaptureRequests.clear();
    }


    @Override
    public void setCropRegion(@Nullable final Rect crop) {
        Log.d(TAG, "setCropRegion(" + crop + ")");
    }

    @NonNull
    @Override
    public FlashMode getFlashMode() {
        return mFlashMode;
    }

    @Override
    public void setFlashMode(@NonNull FlashMode flashMode) {
        mFlashMode = flashMode;
        Log.d(TAG, "setFlashMode(" + mFlashMode + ")");
    }

    @Override
    public void enableTorch(boolean torch) {
        mIsTorchOn = torch;
        Log.d(TAG, "enableTorch(" + torch + ")");
    }

    @Override
    public boolean isTorchOn() {
        return mIsTorchOn;
    }

    @Override
    public void triggerAf() {
        Log.d(TAG, "triggerAf()");
    }

    @Override
    public void triggerAePrecapture() {
        Log.d(TAG, "triggerAePrecapture()");
    }

    @Override
    public void cancelAfAeTrigger(final boolean cancelAfTrigger,
            final boolean cancelAePrecaptureTrigger) {
        Log.d(TAG, "cancelAfAeTrigger(" + cancelAfTrigger + ", "
                + cancelAePrecaptureTrigger + ")");
    }

    @Override
    public void submitCaptureRequests(@NonNull List<CaptureConfig> captureConfigs) {
        mSubmittedCaptureRequests.addAll(captureConfigs);
        mControlUpdateCallback.onCameraControlCaptureRequests(captureConfigs);
        if (mOnNewCaptureRequestListener != null) {
            mOnNewCaptureRequestListener.onNewCaptureRequests(captureConfigs);
        }
    }

    private void updateSessionConfig() {
        mControlUpdateCallback.onCameraControlUpdateSessionConfig(mSessionConfigBuilder.build());
    }

    @Override
    public void startFocusAndMetering(@NonNull FocusMeteringAction action) {
    }

    @Override
    public void cancelFocusAndMetering() {
    }

    /** Sets a listener to be notified when there are new capture request submitted */
    public void setOnNewCaptureRequestListener(@NonNull OnNewCaptureRequestListener listener) {
        mOnNewCaptureRequestListener = listener;
    }

    /** A listener which are used to notify when there are new submitted capture requests */
    public interface OnNewCaptureRequestListener {
        /** Called when there are new submitted capture request */
        void onNewCaptureRequests(@NonNull List<CaptureConfig> captureConfigs);
    }
}
