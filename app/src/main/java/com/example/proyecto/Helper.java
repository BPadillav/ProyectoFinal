package com.example.proyecto;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

public class Helper {

    public static Bitmap matToBitmap(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);
        return bitmap;
    }
}
