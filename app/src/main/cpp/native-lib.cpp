#include <jni.h>
#include <string>
#include <opencv2/opencv.hpp>
#include "json.hpp"

using json = nlohmann::json;
using namespace cv;
using namespace std;

CascadeClassifier faceCascade, eyeCascade, noseCascade, mouthCascade;
json eyeCoordinatesJson;

extern "C" JNIEXPORT void JNICALL
Java_com_example_proyecto_MainActivity_initCascadeClassifiers(
        JNIEnv* env,
        jobject /* this */,
        jstring faceCascadePath,
        jstring eyeCascadePath,
        jstring noseCascadePath,
        jstring mouthCascadePath) {

    const char *nativeFacePath = env->GetStringUTFChars(faceCascadePath, 0);
    const char *nativeEyePath = env->GetStringUTFChars(eyeCascadePath, 0);
    const char *nativeNosePath = env->GetStringUTFChars(noseCascadePath, 0);
    const char *nativeMouthPath = env->GetStringUTFChars(mouthCascadePath, 0);

    faceCascade.load(nativeFacePath);
    eyeCascade.load(nativeEyePath);
    noseCascade.load(nativeNosePath);
    mouthCascade.load(nativeMouthPath);

    env->ReleaseStringUTFChars(faceCascadePath, nativeFacePath);
    env->ReleaseStringUTFChars(eyeCascadePath, nativeEyePath);
    env->ReleaseStringUTFChars(noseCascadePath, nativeNosePath);
    env->ReleaseStringUTFChars(mouthCascadePath, nativeMouthPath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_proyecto_MainActivity_detectFeatures(JNIEnv *env, jobject obj, jlong matAddrInput) {
    Mat &frame = *(Mat *) matAddrInput;

    vector<Rect> faces;
    faceCascade.detectMultiScale(frame, faces, 1.1, 28, 0 | CASCADE_SCALE_IMAGE, Size(30, 30));

    eyeCoordinatesJson.clear();
    for (auto &face : faces) {
        rectangle(frame, face, Scalar(255, 0, 0), 4);

        Mat faceROI = frame(face);

        vector<Rect> eyes;
        eyeCascade.detectMultiScale(faceROI, eyes, 1.1, 28, 0 | CASCADE_SCALE_IMAGE, Size(15, 15));
        vector<Point> eyeCenters;
        for (size_t i = 0; i < eyes.size(); i++) {
            auto &eye = eyes[i];
            Rect eyeRect(face.x + eye.x, face.y + eye.y, eye.width, eye.height);
            rectangle(frame, eyeRect, Scalar(0, 0, 255), 4);
            Point eyeCenter(face.x + eye.x + eye.width / 2, face.y + eye.y + eye.height / 2);
            eyeCenters.push_back(eyeCenter);
            eyeCoordinatesJson["eye_" + to_string(i)] = {eyeCenter.x, eyeCenter.y};
        }

        vector<Rect> noses;
        noseCascade.detectMultiScale(faceROI, noses, 1.1, 28, 0 | CASCADE_SCALE_IMAGE, Size(15, 15));
        for (auto &nose : noses) {
            Rect noseRect(face.x + nose.x, face.y + nose.y, nose.width, nose.height);
            rectangle(frame, noseRect, Scalar(0, 255, 0), 4);
        }

        vector<Rect> mouths;
        mouthCascade.detectMultiScale(faceROI, mouths, 1.1, 28, 0 | CASCADE_SCALE_IMAGE, Size(30, 30));
        for (auto &mouth : mouths) {
            Rect mouthRect(face.x + mouth.x, face.y + mouth.y, mouth.width, mouth.height);
            rectangle(frame, mouthRect, Scalar(255, 255, 0), 4);  // Color cyan (BGR) para el rectángulo
        }

        if (eyes.size() == 2 && noses.size() == 1 && mouths.size() == 1) {
            if (eyeCenters[0].y < face.y + face.height / 2 && eyeCenters[1].y < face.y + face.height / 2) {
                if (noses[0].y > eyeCenters[0].y && noses[0].y > eyeCenters[1].y) {
                    if (mouths[0].y > noses[0].y) {
                    }
                }
            }
        }
    }

    string eyeCoordinatesStr = eyeCoordinatesJson.dump();

    jclass mainActivityClass = env->GetObjectClass(obj);
    jmethodID setEyeCoordinatesMethod = env->GetMethodID(mainActivityClass, "setEyeCoordinates", "(Ljava/lang/String;)V");

    jstring eyeCoordinatesJString = env->NewStringUTF(eyeCoordinatesStr.c_str());

    env->CallVoidMethod(obj, setEyeCoordinatesMethod, eyeCoordinatesJString);

    env->DeleteLocalRef(eyeCoordinatesJString);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_proyecto_MainActivity_getEyeCoordinates(JNIEnv *env, jobject obj) {
    string eyeCoordinatesStr = eyeCoordinatesJson.dump();
    return env->NewStringUTF(eyeCoordinatesStr.c_str());
}
