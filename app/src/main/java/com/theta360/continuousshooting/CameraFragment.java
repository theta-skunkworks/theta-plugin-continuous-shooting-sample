package com.theta360.continuousshooting;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import com.theta360.pluginlibrary.activity.ThetaInfo;
import com.theta360.pluginlibrary.exif.CameraAttitude;
import com.theta360.pluginlibrary.exif.CameraSettings;
import com.theta360.pluginlibrary.exif.Exif;
import com.theta360.pluginlibrary.exif.SensorValues;
import com.theta360.pluginlibrary.exif.Xmp;
import com.theta360.pluginlibrary.exif.values.SphereType;
import com.theta360.pluginlibrary.values.ThetaModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

import static android.content.Context.MODE_PRIVATE;
import static com.theta360.continuousshooting.Constants.burstCount;

public class CameraFragment extends Fragment {
    public static final String DCIM = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM).getPath();
    public static final String DIR = "dir";
    public static final String FILENAME = "fileName";
    private SurfaceHolder mSurfaceHolder;
    private Camera mCamera;
    private CameraAttitude mCameraAttitude;
    private Camera.Parameters mParameters;
    private Camera.CameraInfo mCameraInfo;
    private CFCallback mCallback;
    private int mCameraId;
    private boolean mIsCapturing = false;
    private boolean mIsDuringExposure = false;
    private boolean mIsBusting = false;
    private boolean mIsSurface = false;
    private boolean mIsEnd = false;
    private int count = 0;
    private int dirNo = 0;
    private int fileNo = 0;
    private int nextDirNo = 0;
    private File[] files;


    private SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            mIsSurface = true;
            open();
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            setSurface(surfaceHolder);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            mIsSurface = false;
            close();
        }
    };

    private Camera.ErrorCallback mErrorCallback = new Camera.ErrorCallback() {
        @Override
        public void onError(int error, Camera camera) {

        }
    };

    private Camera.ShutterCallback onShutterCallback = new Camera.ShutterCallback() {

        @Override
        public void onShutter() {
            if (mIsCapturing && !mIsDuringExposure) {
                mIsDuringExposure = true;
                mIsCapturing = false;

                /*
                 * Hold the current value of the attitude sensor
                 * - It will be used later as setting value for Metadata.
                 */
                mCameraAttitude.snapshot();

                if (mCallback != null) {
                    mCallback.onShutter();
                }
            } else if (!mIsCapturing && mIsDuringExposure) {
                mIsDuringExposure = false;

                /*
                 * Acquire the camera parameters for metadata at the completion of exposure
                 */
                mParameters = mCamera.getParameters();
                CameraSettings.setCameraParameters(mParameters);
            } else {
                mIsCapturing = false;
                mIsDuringExposure = false;
            }
        }
    };

    private Camera.PictureCallback onJpegPictureCallback = new Camera.PictureCallback() {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            mParameters.set("RIC_PROC_STITCHING", "RicStaticStitching");
            mCamera.setParameters(mParameters);

            count++;
            if (count == burstCount) {
                mIsEnd = true;
            }
            Timber.d("onPictureTaken");
            /*
             * Create Exif object with image data holding incomplete Metadeta
             */
            Exif exif = new Exif(data, true);

            /*
             * Set Sphere type
             */
            CameraSettings.setSphereType(SphereType.EQUIRECTANGULAR);

            /*
             * Set attitude sensor value
             * - Get the sensor value already held and sets it to the camera settings.
             */
            SensorValues sensorValues = new SensorValues();
            sensorValues.setAttitudeRadian(mCameraAttitude.getAttitudeRadianSnapshot());
            sensorValues.setCompassAccuracy(mCameraAttitude.getAccuracySnapshot());
            CameraSettings.setSensorValues(sensorValues);

            /*
             * Set correct value for Receptor-IFD in MakerNote.
             */
            exif.setExifSphere();
            /*
             * Set correct value for MakerNote.
             */
            exif.setExifMaker();
            /*
             * Get Exif data
             * - Acquires the image data in which the correct Metadata has been set.
             */
            byte[] exifData = exif.getExif();

            String fileUrl = getFileName();
            List<String> fileUrls = new ArrayList<String>();
            fileUrls.add(fileUrl);
            try (FileOutputStream fileOutputStream = new FileOutputStream(fileUrl)) {
                // Get Image size
                Camera.Size picSize = mParameters.getPictureSize();

                int pitch = 0;
                int roll = 0;
                if (!CameraSettings.isZenith()) {
                    pitch = exif.calcPitch();
                    roll = exif.calcRoll();
                }

                /*
                 * Add XMP and write image data to a file.
                 */
                Xmp.setXmp(exifData, fileOutputStream, picSize.width, picSize.height, pitch, roll);
            } catch (IOException e) {
                Timber.e(e);
            }

            mCallback.onPictureTaken(fileUrls.toArray(new String[fileUrls.size()]), mIsEnd);

            if (mIsEnd) {
                mIsCapturing = false;
                mIsBusting = false;
                mIsEnd = false;
                count = 0;
            }

        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        SurfaceView surfaceView = (SurfaceView) view.findViewById(R.id.surfaceView);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(mSurfaceHolderCallback);

    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (context instanceof CFCallback) {
            mCallback = (CFCallback) context;
        }

        /*
         * Attitude sensor start
         */
        mCameraAttitude = new CameraAttitude(context);
        mCameraAttitude.register();
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mIsSurface) {
            open();
            setSurface(mSurfaceHolder);
        }
    }

    @Override
    public void onStop() {
        close();
        super.onStop();
    }

    @Override
    public void onDetach() {
        super.onDetach();

        mCallback = null;

        /*
         * Attitude sensor stop
         */
        mCameraAttitude.unregister();
    }

    public boolean isCapturing() {
        return mIsCapturing;
    }

    public boolean isBusting() {
        return mIsBusting;
    }

    public void takePicture() {
        if (!mIsCapturing && getDirFileNo()) {
            mIsCapturing = true;
            mIsBusting = true;


            mCamera.stopPreview();

            ThetaModel thetaModel = ThetaModel.getValue(ThetaInfo.getThetaModelName());

            CameraSettings.setManufacturer("RICOH");
            CameraSettings.setThetaSerialNumber(ThetaInfo.getThetaSerialNumber());
            CameraSettings.setThetaFirmwareVersion(ThetaInfo.getThetaFirmwareVersion(getContext()));
            CameraSettings.setThetaModel(thetaModel);

            mParameters.setPictureSize(6720, 3360);
            mParameters.set("RIC_SHOOTING_MODE", "RicStillCaptureStdBurst");
            mParameters.set("RIC_DNG_OUTPUT_ENABLED", 0);
            mParameters.set("RIC_AEC_BURST_CAPTURE_NUM", burstCount);
            mParameters.set("RIC_AEC_BURST_BRACKET_STEP ", 0);
            mParameters.set("RIC_AEC_BURST_COMPENSATION", 0);
            mParameters.set("RIC_AEC_BURST_MAX_EXPOSURE_TIME", 41);
            mParameters.set("RIC_AEC_BURST_ENABLE_ISO_CONTROL", 0);
            mCamera.setParameters(mParameters);

            mCamera.takePicture(onShutterCallback, null, onJpegPictureCallback);
            Timber.d("start takePicture");

        }
    }

    private String getFileName() {


        if (dirNo == 0 && fileNo == 0) {
            if (nextDirNo == 0) {
                dirNo = 100;
                fileNo = 1;
            } else {
                dirNo = nextDirNo;
                fileNo = 1;
            }
        }

        // ファイルNoが上限の場合は次のフォルダに格納する
        if (fileNo == 10000) {
            dirNo = nextDirNo;
            fileNo = 1;
        }

        // フォルダが存在するか確認し、存在しない場合は作成
        String folder = DCIM + "/" + dirNo + "_CONT";
        File newDir = new File(folder);
        if (!newDir.exists()) {
            newDir.mkdirs();
        }

        String fileUrl = String.format("%s/CONT%s.JPG", folder, String.format("%04d", fileNo));

        ++fileNo;
        SharedPreferences data = getActivity().getSharedPreferences("DataSave", MODE_PRIVATE);
        SharedPreferences.Editor editor = data.edit();
        editor.putInt("dirNo", dirNo);
        editor.putInt("fileNo", fileNo);
        editor.apply();

        return fileUrl;
    }

    private Map<String, String> getMaxFile(File[] files, String startString) {

        Map<String, String> map = new HashMap<>();

        for (File filesSub : files) {
            String[] filesNameList = filesSub.list();
            Arrays.sort(filesNameList, Collections.reverseOrder());

            // 該当する文字列で始まるファイルを取得
            for (String fileName : filesNameList) {
                if (fileName.startsWith(startString)) {
                    map.put(DIR, filesSub.getPath());
                    map.put(FILENAME, fileName);
                    return map;
                }
            }
        }

        return map;
    }


    private boolean getDirFileNo() {

        // DCIM配下のファイルを取得し、フォルダ名降順に並べる
        files = new File(DCIM).listFiles();
        Arrays.sort(files, Collections.reverseOrder());

        SharedPreferences data = getActivity().getSharedPreferences("DataSave", MODE_PRIVATE);
        dirNo = data.getInt("dirNo", 0);
        fileNo = data.getInt("fileNo", 0);

        // SharedPreferencesに保存されてない場合、DCIM配下の最大ファイル番号を取得
        if (dirNo == 0 && fileNo == 0) {
            Map<String, String> fileMap;
            fileMap = getMaxFile(files, "CONT");

            // ファイルが取得出来た場合はディレクトリ、ファイルのカウントを設定する
            if (!fileMap.isEmpty()) {
                dirNo = Integer.parseInt(fileMap.get(DIR).replace(DCIM + "/", "").replace("_CONT", ""));
                fileNo = Integer.parseInt(fileMap.get(FILENAME).replace("CONT", "").replace(".JPG", ""));
                ++fileNo;
            }
        }

        //　ファイル番号が上限時に次に格納するディレクトリ番号を取得
        for (File filesSub : files) {
            String dcfDir = filesSub.getPath().replace(DCIM + "/", "").substring(0, 3);
            if (dcfDir.matches("[0-9]{3}")) {
                nextDirNo = Integer.parseInt(dcfDir);
                nextDirNo++;
                break;
            }
        }

        //　ファイル番号、ディレクトリ番号が上限値のとき、空きがないか確認してない場合はエラーとする
        if (fileNo > (10000 - burstCount) && nextDirNo == 1000) {

            Map<String, String> fileMapMax;
            fileMapMax = getMaxFile(files, "CONT");
            if (!fileMapMax.isEmpty()) {
                dirNo = Integer.parseInt(fileMapMax.get(DIR).replace(DCIM + "/", "").replace("_CONT", ""));
                fileNo = Integer.parseInt(fileMapMax.get(FILENAME).replace("CONT", "").replace(".JPG", ""));
                ++fileNo;
            }

            if (fileNo > (10000 - burstCount)) {
                mCallback.error();

                return false;
            }
        }
        return true;

    }

    protected void close() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            mCamera.setErrorCallback(null);
            mCamera.release();
            mCamera = null;
            mIsCapturing = false;
            mIsDuringExposure = false;
            mIsBusting = false;
            mIsEnd = false;
            count = 0;
        }
    }

    private void open() {
        if (mCamera == null) {
            int numberOfCameras = Camera.getNumberOfCameras();

            for (int i = 0; i < numberOfCameras; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);

                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    mCameraInfo = info;
                    mCameraId = i;
                }

                mCamera = Camera.open(mCameraId);
            }
            mCamera.setErrorCallback(mErrorCallback);
            mParameters = mCamera.getParameters();

            /**
             * Initialize CameraSettings for Metadata
             */
            CameraSettings.initialize();

            mParameters.set("RIC_SHOOTING_MODE", "RicMonitoring");
            mCamera.setParameters(mParameters);
        }
    }

    private void setSurface(@NonNull SurfaceHolder surfaceHolder) {
        if (mCamera != null) {
            mCamera.stopPreview();

            try {
                mCamera.setPreviewDisplay(surfaceHolder);
                mParameters.setPreviewSize(1920, 960);
                mCamera.setParameters(mParameters);
            } catch (IOException e) {
                e.printStackTrace();
                close();
            }
            mCamera.startPreview();
        }
    }

    public interface CFCallback {
        void onShutter();

        void onPictureTaken(String[] fileUrls, boolean mIsEnd);

        void error();
    }
}
