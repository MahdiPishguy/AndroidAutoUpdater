package com.aspsine.androidappupdater;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import com.aspsine.androidappupdater.demo.SP;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Created by aspsine on 15/10/3.
 */
public class UpdateService extends Service {

    private static final String TAG = UpdateService.class.getSimpleName();

    private static final int ACTION_DOWNLOAD = Integer.MAX_VALUE;

    private static final int ACTION_CANCEL = Integer.MIN_VALUE;

    private static final String EXTRA_ACTION = "extra_action";

    private static final String EXTRA_URL = "extra_url";

    private static final String EXTRA_VERSION_CODE = "extra_version_code";

    private static final String EXTRA_VERSION_NAME = "extra_version_name";

    public static final String ACTION_UPDATE = UpdateService.class.getName() + ".action.update";

    public static final String EXTRA_STATUS = "EXTRA_STATUS";


    private NotificationManagerCompat mNotificationManager;

    private volatile boolean mDownloading;

    public static void download(Context context, String url, int versionCode, String versionName) {
        Intent intent = new Intent(context, UpdateService.class);
        intent.putExtra(EXTRA_ACTION, ACTION_DOWNLOAD);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_VERSION_CODE, versionCode);
        intent.putExtra(EXTRA_VERSION_NAME, versionName);
        context.startService(intent);
    }

    public static void cancelDownload(Context context) {
        Intent intent = new Intent(context, UpdateService.class);
        intent.putExtra(EXTRA_ACTION, ACTION_CANCEL);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = NotificationManagerCompat.from(this);
    }

    @Override
    public void onDestroy() {
        mNotificationManager = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int    action      = intent.getIntExtra(EXTRA_ACTION, -1);
        String url         = intent.getStringExtra(EXTRA_URL);
        int    versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0);
        String versionName = intent.getStringExtra(EXTRA_VERSION_NAME);
        String fileName    = makeFileName(versionCode, versionName);
        switch (action) {
            case ACTION_DOWNLOAD:
                if (!mDownloading) {
                    mDownloading = true;
                    download(url, new File(getDir(), fileName));
                } else {
                    Log.e(TAG, "Downloading.");
                }
                break;
            case ACTION_CANCEL:
                cancel();
                break;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    void download(String url, File file) {
        new Thread(new WeakDownloader(this, mNotificationManager, url, file)).start();
    }

    void cancel() {
        this.mDownloading = false;
    }

    static class WeakDownloader implements Runnable {
        final   WeakReference<UpdateService> mReference;
        final   NotificationManagerCompat    mNotificationManager;
        final   NotificationCompat.Builder   mBuilder;
        final   String                       mUrl;
        final   File                         mFile;
        final   Status                       mStatus;
        private long                         downloadedLength;
        private FileOutputStream             fileOutputStream;
        private InputStream                  inputStream;
        int responseCode = 0;
        int total        = 0;
        private long myLastModified = 0;

        public WeakDownloader(UpdateService updateService, NotificationManagerCompat notificationManager, String url, File file) {
            this.mReference = new WeakReference<>(updateService);
            this.mNotificationManager = notificationManager;
            this.mBuilder = new NotificationCompat.Builder(updateService.getApplicationContext());
            this.mUrl = url;
            this.mFile = file;
            this.mStatus = new Status();
        }

        @Override
        public void run() {
            myLastModified = SP.getInstance().getLong(SP.SharedPrefsTypes.lastUpdate); // get last stored value for this file (use file name or other key)
            int total = 0;

            final URL         url;
            HttpURLConnection httpURLConnection = null;
            try {
                try {
                    url = new URL(mUrl);

                    httpURLConnection = (HttpURLConnection) url.openConnection();
                    httpURLConnection.setDoInput(true);

                    httpURLConnection.setConnectTimeout(30000);
                    httpURLConnection.setReadTimeout(30000);
                    httpURLConnection.setRequestMethod("GET");

                    //*** new way to handle download process
                    total = httpURLConnection.getContentLength();
                    if (mFile.exists()) {
                        if (mFile.length() == total) {
                            //we are done, return.
                            return;
                        } else {
                            //file was not completly donwloaded, now check lastModified:
                            long lastModified = httpURLConnection.getLastModified();//this gets the header "Last-Modified" and convert to long
                            if (lastModified == myLastModified) { //myLastModified should be retrived on each download and stored locally on ur system
                                downloadedLength = mFile.length();
                                Log.e("downloadedLength ", downloadedLength + "");
                                httpURLConnection = (HttpURLConnection) url.openConnection();
                                httpURLConnection.setDoInput(true);

                                httpURLConnection.setConnectTimeout(30000);
                                httpURLConnection.setReadTimeout(30000);
                                httpURLConnection.setRequestMethod("GET");

                                httpURLConnection.setRequestProperty("Range", "bytes=" + downloadedLength + "-");

                                //append mode
                                fileOutputStream = new FileOutputStream(mFile, true);
                            } else {
                                //file was modified after 1st uncompleted-download:
                                //storeLastModified(lastModified, mFile.getName()); // use file name as key. can store in db or file ...
                                SP.getInstance().setLong(SP.SharedPrefsTypes.lastUpdate, lastModified);

                                //don't set ant Range ... we want full download, with a fresh file
                                fileOutputStream = new FileOutputStream(mFile);
                            }//last mod IF

                        }//Length check
                    } else {
                        //file not exist at all, create new file, set no Range we want full download...
                        mFile.createNewFile();
                        fileOutputStream = new FileOutputStream(mFile);
                    }//file exists.

                } catch (IOException e) {
                    e.printStackTrace();
                }
                int responseCode = 0;

                try {
                    responseCode = httpURLConnection.getResponseCode();

                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e("ER UPDATE ", e.getMessage());
                }

                //*****
                if (responseCode == 200 || responseCode == 206) {
                    try {
                        inputStream = httpURLConnection.getInputStream();
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e("IOException ", e.getMessage());
                    }
                    final byte[] buffer   = new byte[4 * 1024];
                    int          length   = -1;
                    int          finished = 0;
                    long         start    = System.currentTimeMillis();
                    try {
                        while ((length = inputStream.read(buffer)) != -1) {
                            if (!isDownloading()) {
                                throw new CanceledException("canceled");
                            }
                            fileOutputStream.write(buffer, 0, length);
                            finished += length;
                            if (System.currentTimeMillis() - start > 1000) {
                                onDownloadProgressing(finished, total);
                                start = System.currentTimeMillis();
                            }
                        }
                        onDownloadCompleted();
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e("ER UPDATE ", e.getMessage());
                    }
                } else {
                    Log.e("responseCode ", responseCode + "");
                }
            } catch (CanceledException e) {
                e.printStackTrace();
                Log.e("ER UPDATE ", e.getMessage());
            }
        }

        private boolean isDownloading() {
            UpdateService updateService = mReference.get();
            if (updateService != null) {
                return updateService.mDownloading;
            }
            return false;
        }

        private void onDownloadStarted() {
            mStatus.clear();
            mStatus.status = Status.STATUS_DOWNLOAD_START;

            sendBroadCast();

            mBuilder.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Updating")
                    .setContentText("Init Download")
                    .setProgress(100, 0, true)
                    .setTicker("Start download");
            notifyNotification();
        }


        private void onDownloadProgressing(int finished, int total) {
            mStatus.clear();
            mStatus.status = Status.STATUS_DOWNLOAD_PROGRESSING;
            mStatus.finished = finished;
            mStatus.total = total;
            mStatus.progress = (int) (finished / (float) total * 100);

            sendBroadCast();

            mBuilder.setContentTitle("Updating")
                    .setContentText("Downloading")
                    .setProgress(100, mStatus.progress, false);
            //notifyNotification();
        }

        private void onDownloadCompleted() {
            mStatus.clear();
            mStatus.status = Status.STATUS_DOWNLOAD_COMPLETE;
            mStatus.progress = 100;
            mStatus.apkPath = mFile.getAbsolutePath();
            sendBroadCast();

            mBuilder.setContentTitle("Updating")
                    .setContentText("Download Complete")
                    .setProgress(100, 100, false)
                    .setTicker("Download Complete");
            notifyNotification();
            mNotificationManager.cancel(0);

            reset();
        }

        private void onDownloadFailed() {
            mStatus.clear();
            mStatus.status = Status.STATUS_DOWNLOAD_FAILED;
            sendBroadCast();

            mBuilder.setContentTitle("Updating")
                    .setContentText("Download Failed")
                    .setTicker("Download Failed");
            notifyNotification();
            mNotificationManager.cancel(0);
            reset();
        }

        private void onDownloadCanceled() {
            mStatus.clear();
            mStatus.status = Status.STATUS_DOWNLOAD_CANCELED;
            sendBroadCast();

            mBuilder.setContentTitle("Updating")
                    .setContentText("Download Canceled")
                    .setTicker("Download Canceled")
                    .setAutoCancel(true);
            notifyNotification();
            mNotificationManager.cancel(0);
            reset();
        }

        void sendBroadCast() {
            UpdateService updateService = mReference.get();
            if (updateService != null) {
                Intent intent = new Intent();
                intent.setAction(ACTION_UPDATE);
                intent.putExtra(EXTRA_STATUS, mStatus);
                updateService.sendBroadcast(intent);
            }
        }

        private void notifyNotification() {
            //mNotificationManager.notify(0, mBuilder.build());
        }

        void reset() {
            UpdateService updateService = mReference.get();
            if (updateService != null) {
                updateService.mDownloading = false;
                updateService.stopSelf();
            }
        }
    }

    private String makeFileName(int versionCode, String versionName) {
        String packageName = this.getPackageManager().getNameForUid(Binder.getCallingUid());
        return String.format(Locale.getDefault(), "%s_%s_%d.apk", packageName, versionName, versionCode);
    }

    public File getDir() {
        File dir = new File(getExternalCacheDir(), "update");
        if (!dir.exists()) {
            dir.mkdir();
        }
        return dir;
    }
}
