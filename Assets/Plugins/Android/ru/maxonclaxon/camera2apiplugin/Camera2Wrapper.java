package ru.maxonclaxon.camera2apiplugin;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class Camera2Wrapper {

    private static final String TAG = "UnityCamera2";
    private Context context;
    private CameraManager manager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    
    private int currentTextureId = -1;
    
    // Храним данные о том, какую камеру открываем
    private String logicalCameraId = "0"; 
    private String targetPhysicalId = null;

    public Camera2Wrapper(Context context) {
        this.context = context;
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }
	
    public String getCameraListInfo() {
        StringBuilder sb = new StringBuilder();
        Set<String> foundIds = new HashSet<>();

        try {
            // А. Стандартный список + Физические камеры
            for (String cameraId : manager.getCameraIdList()) {
                processCameraId(cameraId, "Logic", sb, foundIds);

                // Если Android 9+, ищем скрытые физические камеры внутри логической
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                        Set<String> physicalIds = chars.getPhysicalCameraIds();
                        if (physicalIds != null) {
                            for (String physId : physicalIds) {
                                // Формат ID для физической: "2[0]" (Физ ID 2 внутри Логич 0)
                                processCameraId(physId, "Phys[" + cameraId + "]", sb, foundIds);
                            }
                        }
                    } catch (Exception e) { /* Игнорируем ошибки доступа */ }
                }
            }

            // Brute Force перебираем ID от 0 до 60
            for (int i = 0; i <= 60; i++) {
                String id = String.valueOf(i);
                if (!foundIds.contains(id)) {
                    // Если мы еще не видели эту камеру в стандартном списке - проверяем её
                    try {
                        // Если этот вызов не упадет, значит камера существует!
                        manager.getCameraCharacteristics(id); 
                        processCameraId(id, "Hidden", sb, foundIds);
                    } catch (Exception e) {
                        // Камеры нет, идем дальше
                    }
                }
            }

        } catch (Exception e) { e.printStackTrace(); }
        
        return sb.toString();
    }

    private void processCameraId(String id, String type, StringBuilder sb, Set<String> foundIds) {
        try {
            // Если id составной (например "2[0]"), берем только реальный ID для запроса характеристик
            String realId = id;
            String parentId = "";
            
            if (type.startsWith("Phys")) {
                // Для физических камер характеристики запрашиваем по их реальному ID
                realId = id; 
                parentId = type.substring(type.indexOf("["), type.indexOf("]") + 1);
                id = realId + parentId;
            }

            CameraCharacteristics chars = manager.getCameraCharacteristics(realId);
            
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            String facingStr = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) ? "Front" : "Back";
            
            float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float focal = (focalLengths != null && focalLengths.length > 0) ? focalLengths[0] : 0f;

            sb.append(id).append(":")
              .append(type.replace("Phys" + parentId, "Phys")).append("-").append(facingStr).append(":")
              .append(focal).append("|");
              
            foundIds.add(realId); // Запоминаем, чтобы не дублировать в Brute Force
            
        } catch (Exception e) {
             Log.e(TAG, "Error processing camera " + id);
        }
    }

    public int startCamera(String requestedId, int width, int height) {
        stopCamera();
        startBackgroundThread();

        if (requestedId == null || requestedId.isEmpty()) requestedId = "0";

        // Парсим ID
        if (requestedId.contains("[")) {
            String[] parts = requestedId.split("\\[");
            targetPhysicalId = parts[0];
            logicalCameraId = parts[1].replace("]", "");
            Log.d(TAG, "Opening Physical Camera: " + targetPhysicalId + " via Logical: " + logicalCameraId);
        } else {
            targetPhysicalId = null;
            logicalCameraId = requestedId;
            Log.d(TAG, "Opening Logical Camera: " + logicalCameraId);
        }

        // Создаем OpenGL OES текстуру
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        currentTextureId = textures[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, currentTextureId);
        // Важные фильтры для Adreno/Mali
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        // Сброс конвейера, чтобы текстура точно создалась
        GLES20.glFlush(); 

        // Создаем SurfaceTexture
        surfaceTexture = new SurfaceTexture(currentTextureId);
        surfaceTexture.setDefaultBufferSize(width, height);
        // Не используем listener, обновляем вручную из Unity Update()
        
        surface = new Surface(surfaceTexture);

        try {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return -1;
            
            // Открываем всегда ЛОГИЧЕСКУЮ камеру
            manager.openCamera(logicalCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }
                @Override
                public void onDisconnected(CameraDevice camera) { camera.close(); cameraDevice = null; }
                @Override
                public void onError(CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
            }, backgroundHandler);
        } catch (Exception e) { e.printStackTrace(); return -1; }

        return currentTextureId;
    }

    private void createCaptureSession() {
        if (cameraDevice == null || surface == null) return;
        
        try {
            // Подготовка конфигурации для Физической камеры (Android 9+)
            if (targetPhysicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                
                OutputConfiguration outputConfig = new OutputConfiguration(surface);
                outputConfig.setPhysicalCameraId(targetPhysicalId);

                SessionConfiguration sessionConfig = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Collections.singletonList(outputConfig),
                        new Executor() { @Override public void execute(Runnable command) { backgroundHandler.post(command); }},
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) { startRequest(session, true); }
                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {}
                        }
                );
                cameraDevice.createCaptureSession(sessionConfig);
                
            } else {
                // Стандартный запуск
                cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) { startRequest(session, false); }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {}
                }, backgroundHandler);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startRequest(CameraCaptureSession session, boolean isPhysical) {
        if (cameraDevice == null) return;
        captureSession = session;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface); 

            // Стандартные настройки
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

            // Если мы открыли обычную камеру (не физическую), пробуем сделать Zoom < 1.0
            if (!isPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(cameraDevice.getId());
                    Range<Float> zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    
                    if (zoomRange != null) {
                        float minZoom = zoomRange.getLower();
                        // Если камера поддерживает зум 0.5x или 0.6x
                        if (minZoom < 1.0f) {
                            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoom);
                            Log.d(TAG, "Applied Ultra-Wide ZOOM: " + minZoom);
                        } else {
                            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f);
                        }
                    }
                } catch (Exception z) { Log.e(TAG, "Zoom failed", z); }
            }
            // -----------------------------------------------------
            
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
            Log.d(TAG, "Preview Started!");
            
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void updateTexture() {
        if (surfaceTexture != null) {
            try { surfaceTexture.updateTexImage(); } catch (Exception e) {}
        }
    }

    public void stopCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (surface != null) { surface.release(); surface = null; }
            if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
            if (currentTextureId != -1) {
                int[] textures = new int[]{currentTextureId};
                GLES20.glDeleteTextures(1, textures, 0);
                currentTextureId = -1;
            }
            stopBackgroundThread();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); backgroundThread = null; backgroundHandler = null; } catch (Exception e) {}
        }
    }
}