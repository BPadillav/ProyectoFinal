package com.example.proyecto;

import org.opencv.android.Utils;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_VIDEO_CAPTURE = 2;
    private static final int REQUEST_PERMISSIONS = 100;
    private static final String TAG = "MainActivity";
    private ImageView imageView;
    private TextureView textureView;
    private MediaPlayer mediaPlayer;
    private boolean isProcessingFrame = false;
    private Uri videoUri;
    private File videoFile;
    private String eyeCoordinates = "";

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV initialization failed");
        } else {
            Log.d("OpenCV", "OpenCV initialization succeeded");
        }
        System.loadLibrary("native-lib");
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_PERMISSIONS);
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        textureView = findViewById(R.id.textureView);

        Button btnRecord = findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(v -> {
            if (allPermissionsGranted()) {
                dispatchTakeVideoIntent();
            } else {
                requestPermissions();
            }
        });

        Button btnSendVideo = findViewById(R.id.btnSendVideo);
        btnSendVideo.setOnClickListener(v -> {
            if (videoFile != null && videoFile.exists()) {
                sendVideoToServer(videoFile);
            } else {
                Toast.makeText(MainActivity.this, "No hay video para enviar", Toast.LENGTH_SHORT).show();
            }
        });

        // Cargar los clasificadores de cascada
        String faceCascadePath = copyAssetToCache("haarcascade_frontalface_default.xml");
        String eyeCascadePath = copyAssetToCache("haarcascade_eye.xml");
        String noseCascadePath = copyAssetToCache("haarcascade_mcs_nose.xml");
        String mouthCascadePath = copyAssetToCache("haarcascade_mcs_mouth.xml");
        Log.d(TAG, "faceCascadePath: " + faceCascadePath);
        Log.d(TAG, "eyeCascadePath: " + eyeCascadePath);
        Log.d(TAG, "noseCascadePath: " + noseCascadePath);
        Log.d(TAG, "mouthCascadePath: " + mouthCascadePath);
        initCascadeClassifiers(faceCascadePath, eyeCascadePath, noseCascadePath, mouthCascadePath);
    }

    private void dispatchTakeVideoIntent() {
        Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        if (takeVideoIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takeVideoIntent, REQUEST_VIDEO_CAPTURE);
        } else {
            Log.e(TAG, "No app can handle video capture intent");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VIDEO_CAPTURE && resultCode == RESULT_OK) {
            videoUri = data.getData();
            playVideo(videoUri);

            // Guardar el archivo de video en una ubicación accesible
            videoFile = new File(getRealPathFromURI(videoUri));

            // Mostrar el video y ocultar la imagen
            textureView.setVisibility(View.VISIBLE);
            imageView.setVisibility(View.GONE);
        } else {
            Log.d(TAG, "Capture failed or cancelled");
        }
    }

    private String getRealPathFromURI(Uri uri) {
        String[] projection = {MediaStore.Video.Media.DATA};
        Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(columnIndex);
            cursor.close();
            return path;
        }
        return null;
    }

    private void playVideo(Uri videoUri) {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Surface videoSurface = new Surface(surface);
                mediaPlayer = new MediaPlayer();
                try {
                    mediaPlayer.setDataSource(MainActivity.this, videoUri);
                    mediaPlayer.setSurface(videoSurface);
                    mediaPlayer.setLooping(true);
                    mediaPlayer.prepare();
                    mediaPlayer.start();

                    new Thread(() -> processVideo()).start();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    private void processVideo() {
        Mat mat = new Mat();

        runOnUiThread(() -> {
            textureView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
        });

        new Thread(() -> {
            while (mediaPlayer.isPlaying()) {
                if (!textureView.isAvailable()) continue;

                final Bitmap bitmap = textureView.getBitmap();
                if (bitmap == null) {
                    Log.e(TAG, "Bitmap is null");
                    continue;
                }

                Utils.bitmapToMat(bitmap, mat);

                // Procesar la imagen
                Log.d(TAG, "Detecting features...");
                detectFeatures(mat.getNativeObjAddr());

                // Convertir Mat a Bitmap después del procesamiento
                Bitmap processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(mat, processedBitmap);

                runOnUiThread(() -> imageView.setImageBitmap(processedBitmap));

                // Obtener las coordenadas de los ojos desde el código nativo
                eyeCoordinates = getEyeCoordinates();
            }
        }).start();
    }

    private void sendVideoToServer(File videoFile) {
        OkHttpClient client = new OkHttpClient();
        RequestBody videoBody = RequestBody.create(MediaType.parse("video/mp4"), videoFile);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("video", "video.mp4", videoBody)
                .addFormDataPart("eye_coordinates", eyeCoordinates)
                .build();

        Request request = new Request.Builder()
                .url("http://192.168.0.100:5000/upload_video")
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error al enviar video", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Video enviado exitosamente", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error en la respuesta del servidor", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    public native void initCascadeClassifiers(String faceCascadePath, String eyeCascadePath, String noseCascadePath, String mouthCascadePath);
    public native void detectFeatures(long matAddr);

    public native String getEyeCoordinates();  // Método nativo para obtener las coordenadas de los ojos

    public void setEyeCoordinates(String eyeCoordinatesJson) {
        this.eyeCoordinates = eyeCoordinatesJson;
        Log.d("EyeCoordinates", "Eye coordinates set: " + eyeCoordinatesJson);
    }

    private String copyAssetToCache(String assetName) {
        File cacheFile = new File(getCacheDir(), assetName);
        try (InputStream is = getAssets().open(assetName);
             OutputStream os = new FileOutputStream(cacheFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return cacheFile.getAbsolutePath();
    }
}
