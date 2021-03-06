/*******************************************************************************
 *
 *                          Messenger Android Frontend
 *                        (C) 2013-2016 Nikolai Kudashov
 *                           (C) 2017 Björn Petersen
 *                    Contact: r10s@b44t.com, http://b44t.com
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see http://www.gnu.org/licenses/ .
 *
 ******************************************************************************/


package com.b44t.messenger;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.b44t.ui.Components.AnimatedFileDrawable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;

public class ImageLoader {

    private HashMap<String, Integer> bitmapUseCounts = new HashMap<>();
    private LruCache memCache;
    private HashMap<String, CacheImage> imageLoadingByUrl = new HashMap<>();
    private HashMap<String, CacheImage> imageLoadingByKeys = new HashMap<>();
    private HashMap<Integer, CacheImage> imageLoadingByTag = new HashMap<>();
    private HashMap<String, ThumbGenerateInfo> waitingForQualityThumb = new HashMap<>();
    private HashMap<Integer, String> waitingForQualityThumbByTag = new HashMap<>();
    private DispatchQueue cacheOutQueue = new DispatchQueue("cacheOutQueue");
    private DispatchQueue cacheThumbOutQueue = new DispatchQueue("cacheThumbOutQueue");
    private DispatchQueue thumbGeneratingQueue = new DispatchQueue("thumbGeneratingQueue");
    private DispatchQueue imageLoadQueue = new DispatchQueue("imageLoadQueue");
    private HashMap<String, ThumbGenerateTask> thumbGenerateTasks = new HashMap<>();
    private static byte[] bytes;
    private static byte[] bytesThumb;
    //private static byte[] header = new byte[12];
    //private static byte[] headerThumb = new byte[12];

    private String ignoreRemoval = null;

    private volatile long lastCacheOutTime = 0;
    private int lastImageNum = 0;

    private class ThumbGenerateInfo {
        private int count;
        private TLRPC.FileLocation fileLocation;
        private String filter;
    }

    private class ThumbGenerateTask implements Runnable {

        private File originalPath;
        private int mediaType;
        private TLRPC.FileLocation thumbLocation;
        private String filter;

        public ThumbGenerateTask(int type, File path, TLRPC.FileLocation location, String f) {
            mediaType = type;
            originalPath = path;
            thumbLocation = location;
            filter = f;
        }

        private void removeTask() {
            if (thumbLocation == null) {
                return;
            }
            final String name = FileLoader.getAttachFileName(thumbLocation);
            imageLoadQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    thumbGenerateTasks.remove(name);
                }
            });
        }

        @Override
        public void run() {
            try {
                if (thumbLocation == null) {
                    removeTask();
                    return;
                }
                final String key = thumbLocation.volume_id + "_" + thumbLocation.local_id;
                File thumbFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), "q_" + key + ".jpg");
                if (thumbFile.exists() || !originalPath.exists()) {
                    removeTask();
                    return;
                }
                int size = Math.min(180, Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) / 4);
                Bitmap originalBitmap = null;
                if (mediaType == FileLoader.MEDIA_DIR_IMAGE) {
                    originalBitmap = ImageLoader.loadBitmap(originalPath.toString(), null, size, size, false);
                } else if (mediaType == FileLoader.MEDIA_DIR_VIDEO) {
                    originalBitmap = ThumbnailUtils.createVideoThumbnail(originalPath.toString(), MediaStore.Video.Thumbnails.MINI_KIND);
                } else if (mediaType == FileLoader.MEDIA_DIR_DOCUMENT) {
                    String path = originalPath.toString().toLowerCase();
                    if (!path.endsWith(".jpg") && !path.endsWith(".jpeg") && !path.endsWith(".png") && !path.endsWith(".gif")) {
                        removeTask();
                        return;
                    }
                    originalBitmap = ImageLoader.loadBitmap(path, null, size, size, false);
                }
                if (originalBitmap == null) {
                    removeTask();
                    return;
                }

                int w = originalBitmap.getWidth();
                int h = originalBitmap.getHeight();
                if (w == 0 || h == 0) {
                    removeTask();
                    return;
                }
                float scaleFactor = Math.min((float) w / size, (float) h / size);
                Bitmap scaledBitmap = Bitmaps.createScaledBitmap(originalBitmap, (int) (w / scaleFactor), (int) (h / scaleFactor), true);
                if (scaledBitmap != originalBitmap) {
                    originalBitmap.recycle();
                }
                originalBitmap = scaledBitmap;
                FileOutputStream stream = new FileOutputStream(thumbFile);
                originalBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream);
                try {
                    stream.close();
                } catch (Exception e) {

                }
                final BitmapDrawable bitmapDrawable = new BitmapDrawable(originalBitmap);
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        removeTask();

                        String kf = key;
                        if (filter != null) {
                            kf += "@" + filter;
                        }
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.messageThumbGenerated, bitmapDrawable, kf);
                        memCache.put(kf, bitmapDrawable);
                    }
                });
            } catch (Throwable e) {

                removeTask();
            }
        }
    }

    private class CacheOutTask implements Runnable {
        private Thread runningThread;
        private final Object sync = new Object();

        private CacheImage cacheImage;
        private boolean isCancelled;

        public CacheOutTask(CacheImage image) {
            cacheImage = image;
        }

        @Override
        public void run() {
            synchronized (sync) {
                runningThread = Thread.currentThread();
                Thread.interrupted();
                if (isCancelled) {
                    return;
                }
            }

            if (cacheImage.animatedFile) {
                synchronized (sync) {
                    if (isCancelled) {
                        return;
                    }
                }
                AnimatedFileDrawable fileDrawable = new AnimatedFileDrawable(cacheImage.finalFilePath, cacheImage.filter != null && cacheImage.filter.equals("d"));
                Thread.interrupted();
                onPostExecute(fileDrawable);
            } else {
                Long mediaId = null;
                boolean mediaIsVideo = false;
                Bitmap image = null;
                File cacheFileFinal = cacheImage.finalFilePath;
                boolean canDeleteFile = true;
                /*boolean useNativeWebpLoaded = false;

                if (Build.VERSION.SDK_INT < 19) {
                    RandomAccessFile randomAccessFile = null;
                    try {
                        randomAccessFile = new RandomAccessFile(cacheFileFinal, "r");
                        byte[] bytes;
                        if (cacheImage.thumb) {
                            bytes = headerThumb;
                        } else {
                            bytes = header;
                        }
                        randomAccessFile.readFully(bytes, 0, bytes.length);
                        String str = new String(bytes).toLowerCase();
                        str = str.toLowerCase();
                        if (str.startsWith("riff") && str.endsWith("webp")) {
                            useNativeWebpLoaded = true;
                        }
                        randomAccessFile.close();
                    } catch (Exception e) {

                    } finally {
                        if (randomAccessFile != null) {
                            try {
                                randomAccessFile.close();
                            } catch (Exception e) {

                            }
                        }
                    }
                }*/

                if (cacheImage.thumb) {
                    int blurType = 0;
                    if (cacheImage.filter != null) {
                        if (cacheImage.filter.contains("b2")) {
                            blurType = 3;
                        } else if (cacheImage.filter.contains("b1")) {
                            blurType = 2;
                        } else if (cacheImage.filter.contains("b")) {
                            blurType = 1;
                        }
                    }

                    try {
                        lastCacheOutTime = System.currentTimeMillis();
                        synchronized (sync) {
                            if (isCancelled) {
                                return;
                            }
                        }

                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 1;

                        if (Build.VERSION.SDK_INT < 21) {
                            opts.inPurgeable = true;
                        }

                        /*if (useNativeWebpLoaded) {
                            RandomAccessFile file = new RandomAccessFile(cacheFileFinal, "r");
                            ByteBuffer buffer = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, cacheFileFinal.length());

                            BitmapFactory.Options bmOptions = new BitmapFactory.Options();
                            bmOptions.inJustDecodeBounds = true;
                            Utilities.loadWebpImage(null, buffer, buffer.limit(), bmOptions, true);
                            image = Bitmaps.createBitmap(bmOptions.outWidth, bmOptions.outHeight, Bitmap.Config.ARGB_8888);

                            Utilities.loadWebpImage(image, buffer, buffer.limit(), null, !opts.inPurgeable);
                            file.close();
                        } else*/ {
                            if (opts.inPurgeable) {
                                RandomAccessFile f = new RandomAccessFile(cacheFileFinal, "r");
                                int len = (int) f.length();
                                byte[] data = bytesThumb != null && bytesThumb.length >= len ? bytesThumb : null;
                                if (data == null) {
                                    bytesThumb = data = new byte[len];
                                }
                                f.readFully(data, 0, len);
                                image = BitmapFactory.decodeByteArray(data, 0, len, opts);
                            } else {
                                FileInputStream is = new FileInputStream(cacheFileFinal);
                                image = BitmapFactory.decodeStream(is, null, opts);
                                is.close();
                            }
                        }

                        if (image == null) {
                            if (cacheFileFinal.length() == 0 || cacheImage.filter == null) {
                                cacheFileFinal.delete();
                            }
                        } else {
                            if (blurType == 1) {
                                if (image.getConfig() == Bitmap.Config.ARGB_8888) {
                                    Utilities.blurBitmap(image, 3, opts.inPurgeable ? 0 : 1, image.getWidth(), image.getHeight(), image.getRowBytes());
                                }
                            } else if (blurType == 2) {
                                if (image.getConfig() == Bitmap.Config.ARGB_8888) {
                                    Utilities.blurBitmap(image, 1, opts.inPurgeable ? 0 : 1, image.getWidth(), image.getHeight(), image.getRowBytes());
                                }
                            } else if (blurType == 3) {
                                if (image.getConfig() == Bitmap.Config.ARGB_8888) {
                                    Utilities.blurBitmap(image, 7, opts.inPurgeable ? 0 : 1, image.getWidth(), image.getHeight(), image.getRowBytes());
                                    Utilities.blurBitmap(image, 7, opts.inPurgeable ? 0 : 1, image.getWidth(), image.getHeight(), image.getRowBytes());
                                    Utilities.blurBitmap(image, 7, opts.inPurgeable ? 0 : 1, image.getWidth(), image.getHeight(), image.getRowBytes());
                                }
                            } else if (blurType == 0 && opts.inPurgeable) {
                                Utilities.pinBitmap(image);
                            }
                        }
                    } catch (Throwable e) {

                    }
                } else {
                    try {
                        if (cacheImage.httpUrl != null) {
                            if (cacheImage.httpUrl.startsWith("thumb://")) {
                                int idx = cacheImage.httpUrl.indexOf(":", 8);
                                if (idx >= 0) {
                                    mediaId = Long.parseLong(cacheImage.httpUrl.substring(8, idx));
                                    mediaIsVideo = false;
                                }
                                canDeleteFile = false;
                            } else if (cacheImage.httpUrl.startsWith("vthumb://")) {
                                int idx = cacheImage.httpUrl.indexOf(":", 9);
                                if (idx >= 0) {
                                    mediaId = Long.parseLong(cacheImage.httpUrl.substring(9, idx));
                                    mediaIsVideo = true;
                                }
                                canDeleteFile = false;
                            } else if (!cacheImage.httpUrl.startsWith("http")) {
                                canDeleteFile = false;
                            }
                        }

                        int delay = 20;
                        if (mediaId != null) {
                            delay = 0;
                        }
                        if (delay != 0 && lastCacheOutTime != 0 && lastCacheOutTime > System.currentTimeMillis() - delay && Build.VERSION.SDK_INT < 21) {
                            Thread.sleep(delay);
                        }
                        lastCacheOutTime = System.currentTimeMillis();
                        synchronized (sync) {
                            if (isCancelled) {
                                return;
                            }
                        }

                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 1;

                        float w_filter = 0;
                        float h_filter = 0;
                        boolean blur = false;
                        if (cacheImage.filter != null) {
                            String args[] = cacheImage.filter.split("_");
                            if (args.length >= 2) {
                                w_filter = Float.parseFloat(args[0]) * AndroidUtilities.density;
                                h_filter = Float.parseFloat(args[1]) * AndroidUtilities.density;
                            }
                            if (cacheImage.filter.contains("b")) {
                                blur = true;
                            }
                            if (w_filter != 0 && h_filter != 0) {
                                opts.inJustDecodeBounds = true;

                                if (mediaId != null) {
                                    if (mediaIsVideo) {
                                        MediaStore.Video.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Video.Thumbnails.MINI_KIND, opts);
                                    } else {
                                        MediaStore.Images.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Images.Thumbnails.MINI_KIND, opts);
                                    }
                                } else {
                                    FileInputStream is = new FileInputStream(cacheFileFinal);
                                    image = BitmapFactory.decodeStream(is, null, opts);
                                    is.close();
                                }

                                float photoW = opts.outWidth;
                                float photoH = opts.outHeight;
                                float scaleFactor = Math.max(photoW / w_filter, photoH / h_filter);
                                if (scaleFactor < 1) {
                                    scaleFactor = 1;
                                }
                                opts.inJustDecodeBounds = false;
                                opts.inSampleSize = (int) scaleFactor;
                            }
                        }
                        synchronized (sync) {
                            if (isCancelled) {
                                return;
                            }
                        }

                        if (cacheImage.filter == null || blur || cacheImage.httpUrl != null) {
                            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        } else {
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                        }
                        if (Build.VERSION.SDK_INT < 21) {
                            opts.inPurgeable = true;
                        }

                        opts.inDither = false;
                        if (mediaId != null) {
                            if (mediaIsVideo) {
                                image = MediaStore.Video.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Video.Thumbnails.MINI_KIND, opts);
                            } else {
                                image = MediaStore.Images.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Images.Thumbnails.MINI_KIND, opts);
                            }
                        }
                        if (image == null) {
                            /*if (useNativeWebpLoaded) {
                                RandomAccessFile file = new RandomAccessFile(cacheFileFinal, "r");
                                ByteBuffer buffer = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, cacheFileFinal.length());

                                BitmapFactory.Options bmOptions = new BitmapFactory.Options();
                                bmOptions.inJustDecodeBounds = true;
                                Utilities.loadWebpImage(null, buffer, buffer.limit(), bmOptions, true);
                                image = Bitmaps.createBitmap(bmOptions.outWidth, bmOptions.outHeight, Bitmap.Config.ARGB_8888);

                                Utilities.loadWebpImage(image, buffer, buffer.limit(), null, !opts.inPurgeable);
                                file.close();
                            } else*/ {
                                if (opts.inPurgeable) {
                                    RandomAccessFile f = new RandomAccessFile(cacheFileFinal, "r");
                                    int len = (int) f.length();
                                    byte[] data = bytes != null && bytes.length >= len ? bytes : null;
                                    if (data == null) {
                                        bytes = data = new byte[len];
                                    }
                                    f.readFully(data, 0, len);
                                    image = BitmapFactory.decodeByteArray(data, 0, len, opts);
                                } else {
                                    FileInputStream is = new FileInputStream(cacheFileFinal);
                                    image = BitmapFactory.decodeStream(is, null, opts);
                                    is.close();
                                }
                            }
                        }
                        if (image == null) {
                            if (canDeleteFile && (cacheFileFinal.length() == 0 || cacheImage.filter == null)) {
                                cacheFileFinal.delete();
                            }
                        } else {
                            boolean blured = false;
                            if (cacheImage.filter != null) {
                                float bitmapW = image.getWidth();
                                float bitmapH = image.getHeight();
                                if (!opts.inPurgeable && w_filter != 0 && bitmapW != w_filter && bitmapW > w_filter + 20) {
                                    float scaleFactor = bitmapW / w_filter;
                                    Bitmap scaledBitmap = Bitmaps.createScaledBitmap(image, (int) w_filter, (int) (bitmapH / scaleFactor), true);
                                    if (image != scaledBitmap) {
                                        image.recycle();
                                        image = scaledBitmap;
                                    }
                                }
                                if (image != null && blur && bitmapH < 100 && bitmapW < 100) {
                                    if (image.getConfig() == Bitmap.Config.ARGB_8888) {
                                        Utilities.blurBitmap(image, 3, opts.inPurgeable ? 0 : 1, image.getWidth(), image.getHeight(), image.getRowBytes());
                                    }
                                    blured = true;
                                }
                            }
                            if (!blured && opts.inPurgeable) {
                                Utilities.pinBitmap(image);
                            }
                        }
                    } catch (Throwable e) {
                        //don't promt
                    }
                }
                Thread.interrupted();
                onPostExecute(image != null ? new BitmapDrawable(image) : null);
            }
        }

        private void onPostExecute(final BitmapDrawable bitmapDrawable) {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    BitmapDrawable toSet = null;
                    if (bitmapDrawable instanceof AnimatedFileDrawable) {
                        toSet = bitmapDrawable;
                    } else if (bitmapDrawable != null) {
                        toSet = memCache.get(cacheImage.key);
                        if (toSet == null) {
                            memCache.put(cacheImage.key, bitmapDrawable);
                            toSet = bitmapDrawable;
                        } else {
                            Bitmap image = bitmapDrawable.getBitmap();
                            image.recycle();
                        }
                    }
                    final BitmapDrawable toSetFinal = toSet;
                    imageLoadQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            cacheImage.setImageAndClear(toSetFinal);
                        }
                    });
                }
            });
        }

        public void cancel() {
            synchronized (sync) {
                try {
                    isCancelled = true;
                    if (runningThread != null) {
                        runningThread.interrupt();
                    }
                } catch (Exception e) {
                    //don't promt
                }
            }
        }
    }

    /*
    public class VMRuntimeHack {
        private Object runtime = null;
        private Method trackAllocation = null;
        private Method trackFree = null;

        public boolean trackAlloc(long size) {
            if (runtime == null) {
                return false;
            }
            try {
                Object res = trackAllocation.invoke(runtime, size);
                return (res instanceof Boolean) ? (Boolean) res : true;
            } catch (Exception e) {
                return false;
            }
        }

        public boolean trackFree(long size) {
            if (runtime == null) {
                return false;
            }
            try {
                Object res = trackFree.invoke(runtime, size);
                return (res instanceof Boolean) ? (Boolean) res : true;
            } catch (Exception e) {
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        public VMRuntimeHack() {
            try {
                Class cl = Class.forName("dalvik.system.VMRuntime");
                Method getRt = cl.getMethod("getRuntime", new Class[0]);
                Object[] objects = new Object[0];
                runtime = getRt.invoke(null, objects);
                trackAllocation = cl.getMethod("trackExternalAllocation", new Class[]{long.class});
                trackFree = cl.getMethod("trackExternalFree", new Class[]{long.class});
            } catch (Exception e) {

                runtime = null;
                trackAllocation = null;
                trackFree = null;
            }
        }
    }
    */

    private class CacheImage {
        protected String key;
        protected String url;
        protected String filter;
        protected String ext;
        protected TLObject location;
        protected boolean animatedFile;

        protected File finalFilePath;
        protected File tempFilePath;
        protected boolean thumb;

        protected String httpUrl;
        protected CacheOutTask cacheTask;

        protected ArrayList<ImageReceiver> imageReceiverArray = new ArrayList<>();

        public void addImageReceiver(ImageReceiver imageReceiver) {
            boolean exist = false;
            for (ImageReceiver v : imageReceiverArray) {
                if (v == imageReceiver) {
                    exist = true;
                    break;
                }
            }
            if (!exist) {
                imageReceiverArray.add(imageReceiver);
                imageLoadingByTag.put(imageReceiver.getTag(thumb), this);
            }
        }

        public void removeImageReceiver(ImageReceiver imageReceiver) {
            for (int a = 0; a < imageReceiverArray.size(); a++) {
                ImageReceiver obj = imageReceiverArray.get(a);
                if (obj == null || obj == imageReceiver) {
                    imageReceiverArray.remove(a);
                    if (obj != null) {
                        imageLoadingByTag.remove(obj.getTag(thumb));
                    }
                    a--;
                }
            }
            if (imageReceiverArray.size() == 0) {
                for (int a = 0; a < imageReceiverArray.size(); a++) {
                    imageLoadingByTag.remove(imageReceiverArray.get(a).getTag(thumb));
                }
                imageReceiverArray.clear();
                if (location != null) {
                    if (location instanceof TLRPC.FileLocation) {
                        //FileLoader.getInstance().cancelLoadFile((TLRPC.FileLocation) location, ext);
                    } else if (location instanceof TLRPC.Document) {
                        //FileLoader.getInstance().cancelLoadFile((TLRPC.Document) location);
                    }
                }
                if (cacheTask != null) {
                    if (thumb) {
                        cacheThumbOutQueue.cancelRunnable(cacheTask);
                    } else {
                        cacheOutQueue.cancelRunnable(cacheTask);
                    }
                    cacheTask.cancel();
                    cacheTask = null;
                }
                //if (httpTask != null) {
                //    httpTasks.remove(httpTask);
                //    httpTask.cancel(true);
                //    httpTask = null;
                //}
                if (url != null) {
                    imageLoadingByUrl.remove(url);
                }
                if (key != null) {
                    imageLoadingByKeys.remove(key);
                }
            }
        }

        public void setImageAndClear(final BitmapDrawable image) {
            if (image != null) {
                final ArrayList<ImageReceiver> finalImageReceiverArray = new ArrayList<>(imageReceiverArray);
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (image instanceof AnimatedFileDrawable) {
                            boolean imageSet = false;
                            AnimatedFileDrawable fileDrawable = (AnimatedFileDrawable) image;
                            for (int a = 0; a < finalImageReceiverArray.size(); a++) {
                                ImageReceiver imgView = finalImageReceiverArray.get(a);
                                if (imgView.setImageBitmapByKey(a == 0 ? fileDrawable : fileDrawable.makeCopy(), key, thumb, false)) {
                                    imageSet = true;
                                }
                            }
                            if (!imageSet) {
                                ((AnimatedFileDrawable) image).recycle();
                            }
                        } else {
                            for (int a = 0; a < finalImageReceiverArray.size(); a++) {
                                ImageReceiver imgView = finalImageReceiverArray.get(a);
                                imgView.setImageBitmapByKey(image, key, thumb, false);
                            }
                        }
                    }
                });
            }
            for (int a = 0; a < imageReceiverArray.size(); a++) {
                ImageReceiver imageReceiver = imageReceiverArray.get(a);
                imageLoadingByTag.remove(imageReceiver.getTag(thumb));
            }
            imageReceiverArray.clear();
            if (url != null) {
                imageLoadingByUrl.remove(url);
            }
            if (key != null) {
                imageLoadingByKeys.remove(key);
            }
        }
    }

    private static volatile ImageLoader Instance = null;

    public static ImageLoader getInstance() {
        ImageLoader localInstance = Instance;
        if (localInstance == null) {
            synchronized (ImageLoader.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new ImageLoader();
                }
            }
        }
        return localInstance;
    }

    public ImageLoader() {

        cacheOutQueue.setPriority(Thread.MIN_PRIORITY);
        cacheThumbOutQueue.setPriority(Thread.MIN_PRIORITY);
        thumbGeneratingQueue.setPriority(Thread.MIN_PRIORITY);
        imageLoadQueue.setPriority(Thread.MIN_PRIORITY);

        int cacheSize = Math.min(15, ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass() / 7) * 1024 * 1024;

        memCache = new LruCache(cacheSize) {
            @Override
            protected int sizeOf(String key, BitmapDrawable value) {
                return value.getBitmap().getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, final BitmapDrawable oldValue, BitmapDrawable newValue) {
                if (ignoreRemoval != null && key != null && ignoreRemoval.equals(key)) {
                    return;
                }
                final Integer count = bitmapUseCounts.get(key);
                if (count == null || count == 0) {
                    Bitmap b = oldValue.getBitmap();
                    if (!b.isRecycled()) {
                        b.recycle();
                    }
                }
            }
        };

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context arg0, Intent intent) {
                Runnable r = new Runnable() {
                    public void run() {
                        checkMediaPaths();
                    }
                };
                if (Intent.ACTION_MEDIA_UNMOUNTED.equals(intent.getAction())) {
                    AndroidUtilities.runOnUIThread(r, 1000);
                } else {
                    r.run();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_CHECKING);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_NOFS);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addDataScheme("file");
        ApplicationLoader.applicationContext.registerReceiver(receiver, filter);

        checkMediaPaths();
    }

    public void checkMediaPaths() {
        HashMap<Integer, File> mediaDirs = new HashMap<>();
        File cachePath = new File(MrMailbox.getBlobdir());
        try {
            new File(cachePath, ".nomedia").createNewFile();
        } catch (Exception e) {
        }

        mediaDirs.put(FileLoader.MEDIA_DIR_CACHE, cachePath);
        FileLoader.getInstance().setMediaDirs(mediaDirs);
    }

    public void incrementUseCount(String key) {
        Integer count = bitmapUseCounts.get(key);
        if (count == null) {
            bitmapUseCounts.put(key, 1);
        } else {
            bitmapUseCounts.put(key, count + 1);
        }
    }

    public boolean decrementUseCount(String key) {
        Integer count = bitmapUseCounts.get(key);
        if (count == null) {
            return true;
        }
        if (count == 1) {
            bitmapUseCounts.remove(key);
            return true;
        } else {
            bitmapUseCounts.put(key, count - 1);
        }
        return false;
    }

    public void removeImage(String key) {
        bitmapUseCounts.remove(key);
        memCache.remove(key);
    }

    public boolean isInCache(String key) {
        return memCache.get(key) != null;
    }

    public void clearMemory() {
        memCache.evictAll();
    }

    private void removeFromWaitingForThumb(Integer TAG) {
        String location = waitingForQualityThumbByTag.get(TAG);
        if (location != null) {
            ThumbGenerateInfo info = waitingForQualityThumb.get(location);
            if (info != null) {
                info.count--;
                if (info.count == 0) {
                    waitingForQualityThumb.remove(location);
                }
            }
            waitingForQualityThumbByTag.remove(TAG);
        }
    }

    public void cancelLoadingForImageReceiver(final ImageReceiver imageReceiver, final int type) {
        if (imageReceiver == null) {
            return;
        }
        imageLoadQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                int start = 0;
                int count = 2;
                if (type == 1) {
                    count = 1;
                } else if (type == 2) {
                    start = 1;
                }
                for (int a = start; a < count; a++) {
                    Integer TAG = imageReceiver.getTag(a == 0);
                    if (a == 0) {
                        removeFromWaitingForThumb(TAG);
                    }
                    if (TAG != null) {
                        CacheImage ei = imageLoadingByTag.get(TAG);
                        if (ei != null) {
                            ei.removeImageReceiver(imageReceiver);
                        }
                    }
                }
            }
        });
    }

    private void generateThumb(int mediaType, File originalPath, TLRPC.FileLocation thumbLocation, String filter) {
        if (mediaType != FileLoader.MEDIA_DIR_IMAGE && mediaType != FileLoader.MEDIA_DIR_VIDEO && mediaType != FileLoader.MEDIA_DIR_DOCUMENT || originalPath == null || thumbLocation == null) {
            return;
        }
        String name = FileLoader.getAttachFileName(thumbLocation);
        ThumbGenerateTask task = thumbGenerateTasks.get(name);
        if (task == null) {
            task = new ThumbGenerateTask(mediaType, originalPath, thumbLocation, filter);
            thumbGeneratingQueue.postRunnable(task);
        }
    }

    private void createLoadOperationForImageReceiver(final ImageReceiver imageReceiver, final String key, final String url, final String ext, final TLObject imageLocation, final String httpLocation, final String filter, final int size, final boolean cacheOnly, final int thumb) {
        if (imageReceiver == null || url == null || key == null) {
            return;
        }
        Integer TAG = imageReceiver.getTag(thumb != 0);
        if (TAG == null) {
            imageReceiver.setTag(TAG = lastImageNum, thumb != 0);
            lastImageNum++;
            if (lastImageNum == Integer.MAX_VALUE) {
                lastImageNum = 0;
            }
        }

        final Integer finalTag = TAG;
        final boolean finalIsNeedsQualityThumb = imageReceiver.isNeedsQualityThumb();
        final MessageObject parentMessageObject = imageReceiver.getParentMessageObject();
        final boolean shouldGenerateQualityThumb = imageReceiver.isShouldGenerateQualityThumb();
        imageLoadQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                boolean added = false;
                if (thumb != 2) {
                    CacheImage alreadyLoadingUrl = imageLoadingByUrl.get(url);
                    CacheImage alreadyLoadingCache = imageLoadingByKeys.get(key);
                    CacheImage alreadyLoadingImage = imageLoadingByTag.get(finalTag);
                    if (alreadyLoadingImage != null) {
                        if (alreadyLoadingImage == alreadyLoadingUrl || alreadyLoadingImage == alreadyLoadingCache) {
                            added = true;
                        } else {
                            alreadyLoadingImage.removeImageReceiver(imageReceiver);
                        }
                    }

                    if (!added && alreadyLoadingCache != null) {
                        alreadyLoadingCache.addImageReceiver(imageReceiver);
                        added = true;
                    }
                    if (!added && alreadyLoadingUrl != null) {
                        alreadyLoadingUrl.addImageReceiver(imageReceiver);
                        added = true;
                    }
                }

                if (!added) {
                    boolean onlyCache = false;
                    File cacheFile = null;

                    if (httpLocation != null) {
                        if (!httpLocation.startsWith("http")) {
                            onlyCache = true;
                            if (httpLocation.startsWith("thumb://")) {
                                int idx = httpLocation.indexOf(":", 8);
                                if (idx >= 0) {
                                    cacheFile = new File(httpLocation.substring(idx + 1));
                                }
                            } else if (httpLocation.startsWith("vthumb://")) {
                                int idx = httpLocation.indexOf(":", 9);
                                if (idx >= 0) {
                                    cacheFile = new File(httpLocation.substring(idx + 1));
                                }
                            } else {
                                cacheFile = new File(httpLocation);
                            }
                        }
                    } else if (thumb != 0) {
                        if (finalIsNeedsQualityThumb) {
                            cacheFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), "q_" + url);
                            if (!cacheFile.exists()) {
                                cacheFile = null;
                            }
                        }

                        if (parentMessageObject != null) {
                            File attachPath = null;
                            if (parentMessageObject.messageOwner.attachPath != null && parentMessageObject.messageOwner.attachPath.length() > 0) {
                                attachPath = new File(parentMessageObject.messageOwner.attachPath);
                                if (!attachPath.exists()) {
                                    attachPath = null;
                                }
                            }
                            if (attachPath == null) {
                                attachPath = FileLoader.getPathToMessage(parentMessageObject.messageOwner);
                            }
                            if (finalIsNeedsQualityThumb && cacheFile == null) {
                                String location = parentMessageObject.getFileName();
                                ThumbGenerateInfo info = waitingForQualityThumb.get(location);
                                if (info == null) {
                                    info = new ThumbGenerateInfo();
                                    info.fileLocation = (TLRPC.TL_fileLocation) imageLocation;
                                    info.filter = filter;
                                    waitingForQualityThumb.put(location, info);
                                }
                                info.count++;
                                waitingForQualityThumbByTag.put(finalTag, location);
                            }
                            if (attachPath.exists() && shouldGenerateQualityThumb) {
                                generateThumb(parentMessageObject.getFileType(), attachPath, (TLRPC.TL_fileLocation) imageLocation, filter);
                            }
                        }
                    }

                    if (thumb != 2) {
                        CacheImage img = new CacheImage();
                        if (httpLocation != null && !httpLocation.startsWith("vthumb") && !httpLocation.startsWith("thumb") && (httpLocation.endsWith("mp4") || httpLocation.endsWith("gif")) || imageLocation instanceof TLRPC.Document && MessageObject.isGifDocument((TLRPC.Document) imageLocation)) {
                            img.animatedFile = true;
                        }

                        if (cacheFile == null) {
                            if( imageLocation != null
                             && imageLocation instanceof TLRPC.FileLocation
                             && ((TLRPC.FileLocation) imageLocation).mr_path != null ) {
                                cacheFile = new File(((TLRPC.FileLocation) imageLocation).mr_path);
                            }
                            else if (cacheOnly || size == 0 || httpLocation != null) {
                                cacheFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), url);
                            } else if (imageLocation instanceof TLRPC.Document) {
                                cacheFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_DOCUMENT), url);
                            } else {
                                cacheFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_IMAGE), url);
                            }
                        }

                        img.thumb = thumb != 0;
                        img.key = key;
                        img.filter = filter;
                        img.httpUrl = httpLocation;
                        img.ext = ext;
                        img.addImageReceiver(imageReceiver);
                        if (onlyCache || cacheFile.exists()) {
                            img.finalFilePath = cacheFile;
                            img.cacheTask = new CacheOutTask(img);
                            imageLoadingByKeys.put(key, img);
                            if (thumb != 0) {
                                cacheThumbOutQueue.postRunnable(img.cacheTask);
                            } else {
                                cacheOutQueue.postRunnable(img.cacheTask);
                            }
                        } else {
                            img.url = url;
                            img.location = imageLocation;
                            imageLoadingByUrl.put(url, img);
                            if (httpLocation == null) {
                                if (imageLocation instanceof TLRPC.FileLocation) {
                                    TLRPC.FileLocation location = (TLRPC.FileLocation) imageLocation;
                                    //FileLoader.getInstance().loadFile(location, ext, size, size == 0 || location.key != null || cacheOnly);
                                } else if (imageLocation instanceof TLRPC.Document) {
                                    //FileLoader.getInstance().loadFile((TLRPC.Document) imageLocation, true, cacheOnly);
                                }
                            } else {
                                String file = Utilities.MD5(httpLocation);
                                File cacheDir = FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE);
                                img.tempFilePath = new File(cacheDir, file + "_temp.jpg");
                                img.finalFilePath = cacheFile;
                                //img.httpTask = new HttpImageTask(img, size);
                                //httpTasks.add(img.httpTask);
                                //runHttpTasks(false);
                            }
                        }
                    }
                }
            }
        });
    }

    public void loadImageForImageReceiver(ImageReceiver imageReceiver) {
        if (imageReceiver == null) {
            return;
        }

        String key = imageReceiver.getKey();
        if (key != null) {
            BitmapDrawable bitmapDrawable = memCache.get(key);
            if (bitmapDrawable != null) {
                cancelLoadingForImageReceiver(imageReceiver, 0);
                if (!imageReceiver.isForcePreview()) {
                    imageReceiver.setImageBitmapByKey(bitmapDrawable, key, false, true);
                    return;
                }
            }
        }
        boolean thumbSet = false;
        String thumbKey = imageReceiver.getThumbKey();
        if (thumbKey != null) {
            BitmapDrawable bitmapDrawable = memCache.get(thumbKey);
            if (bitmapDrawable != null) {
                imageReceiver.setImageBitmapByKey(bitmapDrawable, thumbKey, true, true);
                cancelLoadingForImageReceiver(imageReceiver, 1);
                thumbSet = true;
            }
        }

        TLRPC.FileLocation thumbLocation = imageReceiver.getThumbLocation();
        TLObject imageLocation = imageReceiver.getImageLocation();
        String httpLocation = imageReceiver.getHttpImageLocation();

        boolean saveImageToCache = false;

        String url = null;
        String thumbUrl = null;
        key = null;
        thumbKey = null;
        String ext = imageReceiver.getExt();
        if (ext == null) {
            ext = "jpg";
        }
        if (httpLocation != null) {
            key = Utilities.MD5(httpLocation);
            url = key + "." + getHttpUrlExtension(httpLocation, "jpg");
        } else if (imageLocation != null) {
            if (imageLocation instanceof TLRPC.FileLocation) {
                TLRPC.FileLocation location = (TLRPC.FileLocation) imageLocation;
                key = location.volume_id + "_" + location.local_id;
                url = key + "." + ext;
                if (imageReceiver.getExt() != null || location.key != null || location.volume_id == Integer.MIN_VALUE && location.local_id < 0) {
                    saveImageToCache = true;
                }
            } else if (imageLocation instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) imageLocation;
                if (document.id == 0 || document.dc_id == 0) {
                    return;
                }
                key = document.dc_id + "_" + document.id;
                String docExt = FileLoader.getDocumentFileName(document);
                int idx;
                if (docExt == null || (idx = docExt.lastIndexOf('.')) == -1) {
                    docExt = "";
                } else {
                    docExt = docExt.substring(idx);
                }
                if (docExt.length() <= 1) {
                    if (document.mime_type != null && document.mime_type.equals("video/mp4")) {
                        docExt = ".mp4";
                    } else {
                        docExt = "";
                    }
                }
                url = key + docExt;
                if (thumbKey != null) {
                    thumbUrl = thumbKey + "." + ext;
                }
                saveImageToCache = !MessageObject.isGifDocument(document);
            }
            if (imageLocation == thumbLocation) {
                imageLocation = null;
                key = null;
                url = null;
            }
        }

        if (thumbLocation != null) {
            thumbKey = thumbLocation.volume_id + "_" + thumbLocation.local_id;
            thumbUrl = thumbKey + "." + ext;
        }

        String filter = imageReceiver.getFilter();
        String thumbFilter = imageReceiver.getThumbFilter();
        if (key != null && filter != null) {
            key += "@" + filter;
        }
        if (thumbKey != null && thumbFilter != null) {
            thumbKey += "@" + thumbFilter;
        }

        if (httpLocation != null) {
            createLoadOperationForImageReceiver(imageReceiver, thumbKey, thumbUrl, ext, thumbLocation, null, thumbFilter, 0, true, thumbSet ? 2 : 1);
            createLoadOperationForImageReceiver(imageReceiver, key, url, ext, null, httpLocation, filter, 0, true, 0);
        } else {
            createLoadOperationForImageReceiver(imageReceiver, thumbKey, thumbUrl, ext, thumbLocation, null, thumbFilter, 0, true, thumbSet ? 2 : 1);
            createLoadOperationForImageReceiver(imageReceiver, key, url, ext, imageLocation, null, filter, imageReceiver.getSize(), saveImageToCache || imageReceiver.getCacheOnly(), 0);
        }
    }

    public static Bitmap loadBitmap(String path, Uri uri, float maxWidth, float maxHeight, boolean useMaxScale) {
        BitmapFactory.Options bmOptions = new BitmapFactory.Options();
        bmOptions.inJustDecodeBounds = true;
        InputStream inputStream = null;

        if (path == null && uri != null && uri.getScheme() != null) {
            if (uri.getScheme().contains("file")) {
                path = uri.getPath();
            } else {
                try {
                    path = AndroidUtilities.getPath(uri);
                } catch (Throwable e) {

                }
            }
        }

        if (path != null) {
            BitmapFactory.decodeFile(path, bmOptions);
        } else if (uri != null) {
            try {
                inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(inputStream, null, bmOptions);
                inputStream.close();
                inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            } catch (Throwable e) {

                return null;
            }
        }
        float photoW = bmOptions.outWidth;
        float photoH = bmOptions.outHeight;
        float scaleFactor = useMaxScale ? Math.max(photoW / maxWidth, photoH / maxHeight) : Math.min(photoW / maxWidth, photoH / maxHeight);
        if (scaleFactor < 1) {
            scaleFactor = 1;
        }
        bmOptions.inJustDecodeBounds = false;
        bmOptions.inSampleSize = (int) scaleFactor;
        bmOptions.inPurgeable = Build.VERSION.SDK_INT < 21;

        String exifPath = null;
        if (path != null) {
            exifPath = path;
        } else if (uri != null) {
            exifPath = AndroidUtilities.getPath(uri);
        }

        Matrix matrix = null;

        if (exifPath != null) {
            ExifInterface exif;
            try {
                exif = new ExifInterface(exifPath);
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                matrix = new Matrix();
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        matrix.postRotate(90);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        matrix.postRotate(180);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        matrix.postRotate(270);
                        break;
                }
            } catch (Throwable e) {

            }
        }

        Bitmap b = null;
        if (path != null) {
            try {
                b = BitmapFactory.decodeFile(path, bmOptions);
                if (b != null) {
                    if (bmOptions.inPurgeable) {
                        Utilities.pinBitmap(b);
                    }
                    Bitmap newBitmap = Bitmaps.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);
                    if (newBitmap != b) {
                        b.recycle();
                        b = newBitmap;
                    }
                }
            } catch (Throwable e) {

                ImageLoader.getInstance().clearMemory();
                try {
                    if (b == null) {
                        b = BitmapFactory.decodeFile(path, bmOptions);
                        if (b != null && bmOptions.inPurgeable) {
                            Utilities.pinBitmap(b);
                        }
                    }
                    if (b != null) {
                        Bitmap newBitmap = Bitmaps.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);
                        if (newBitmap != b) {
                            b.recycle();
                            b = newBitmap;
                        }
                    }
                } catch (Throwable e2) {
                }
            }
        } else if (uri != null) {
            try {
                b = BitmapFactory.decodeStream(inputStream, null, bmOptions);
                if (b != null) {
                    if (bmOptions.inPurgeable) {
                        Utilities.pinBitmap(b);
                    }
                    Bitmap newBitmap = Bitmaps.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);
                    if (newBitmap != b) {
                        b.recycle();
                        b = newBitmap;
                    }
                }
            } catch (Throwable e) {

            } finally {
                try {
                    inputStream.close();
                } catch (Throwable e) {

                }
            }
        }

        return b;
    }

    private static TLRPC.PhotoSize scaleAndSaveImageInternal(File cacheFile, Bitmap bitmap, int w, int h, float photoW, float photoH, float scaleFactor, int quality, boolean cache, boolean scaleAnyway) throws Exception {
        Bitmap scaledBitmap;
        if (scaleFactor > 1 || scaleAnyway) {
            scaledBitmap = Bitmaps.createScaledBitmap(bitmap, w, h, true);
        } else {
            scaledBitmap = bitmap;
        }

        TLRPC.TL_fileLocation location = new TLRPC.TL_fileLocation();
        location.volume_id = Integer.MIN_VALUE;
        location.dc_id = Integer.MIN_VALUE;
        location.local_id = UserConfig.lastLocalId;
        UserConfig.lastLocalId--;
        TLRPC.PhotoSize size = new TLRPC.TL_photoSize();
        size.location = location;
        size.w = scaledBitmap.getWidth();
        size.h = scaledBitmap.getHeight();
        if (size.w <= 100 && size.h <= 100) {
            size.type = "s";
        } else if (size.w <= 320 && size.h <= 320) {
            size.type = "m";
        } else if (size.w <= 800 && size.h <= 800) {
            size.type = "x";
        } else if (size.w <= 1280 && size.h <= 1280) {
            size.type = "y";
        } else {
            size.type = "w";
        }

        if( cacheFile == null ) {
            String fileName = location.volume_id + "_" + location.local_id + ".jpg";
            cacheFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
        }

        FileOutputStream stream = new FileOutputStream(cacheFile);
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
        if (cache) {
            ByteArrayOutputStream stream2 = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream2);
            size.bytes = stream2.toByteArray();
            size.size = size.bytes.length;
            stream2.close();
        } else {
            size.size = (int) stream.getChannel().size();
        }
        stream.close();
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle();
        }

        return size;
    }

    public static TLRPC.PhotoSize scaleAndSaveImage(Bitmap bitmap, float maxWidth, float maxHeight, int quality, boolean cache) {
        return scaleAndSaveImage(null, bitmap, maxWidth, maxHeight, quality, cache, 0, 0);
    }

    public static TLRPC.PhotoSize scaleAndSaveImage(Bitmap bitmap, float maxWidth, float maxHeight, int quality, boolean cache, int minWidth, int minHeight) {
        return scaleAndSaveImage(null, bitmap, maxWidth, maxHeight, quality, cache, minWidth, minHeight);
    }

    public static TLRPC.PhotoSize scaleAndSaveImage(File cacheFile, Bitmap bitmap, float maxWidth, float maxHeight, int quality, boolean cache) {
        return scaleAndSaveImage(cacheFile, bitmap, maxWidth, maxHeight, quality, cache, 0, 0);
    }

    private static TLRPC.PhotoSize scaleAndSaveImage(File cacheFile, Bitmap bitmap, float maxWidth, float maxHeight, int quality, boolean cache, int minWidth, int minHeight) {
        if (bitmap == null) {
            return null;
        }
        float photoW = bitmap.getWidth();
        float photoH = bitmap.getHeight();
        if (photoW == 0 || photoH == 0) {
            return null;
        }
        boolean scaleAnyway = false;
        float scaleFactor = Math.max(photoW / maxWidth, photoH / maxHeight);
        if (minWidth != 0 && minHeight != 0 && (photoW < minWidth || photoH < minHeight)) {
            if (photoW < minWidth && photoH > minHeight) {
                scaleFactor = photoW / minWidth;
            } else if (photoW > minWidth && photoH < minHeight) {
                scaleFactor = photoH / minHeight;
            } else {
                scaleFactor = Math.max(photoW / minWidth, photoH / minHeight);
            }
            scaleAnyway = true;
        }
        int w = (int) (photoW / scaleFactor);
        int h = (int) (photoH / scaleFactor);
        if (h == 0 || w == 0) {
            return null;
        }

        try {
            return scaleAndSaveImageInternal(cacheFile, bitmap, w, h, photoW, photoH, scaleFactor, quality, cache, scaleAnyway);
        } catch (Throwable e) {

            ImageLoader.getInstance().clearMemory();
            System.gc();
            try {
                return scaleAndSaveImageInternal(cacheFile, bitmap, w, h, photoW, photoH, scaleFactor, quality, cache, scaleAnyway);
            } catch (Throwable e2) {
                return null;
            }
        }
    }

    public static String getHttpUrlExtension(String url, String defaultExt) {
        String ext = null;
        int idx = url.lastIndexOf('.');
        if (idx != -1) {
            ext = url.substring(idx + 1);
        }
        if (ext == null || ext.length() == 0 || ext.length() > 4) {
            ext = defaultExt;
        }
        return ext;
    }

    /*
    public static void saveMessageThumbs(TLRPC.Message message) {
        TLRPC.PhotoSize photoSize = null;
        if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
            for (TLRPC.PhotoSize size : message.media.photo.sizes) {
                if (size instanceof TLRPC.TL_photoCachedSize) {
                    photoSize = size;
                    break;
                }
            }
        } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
            if (message.media.document.thumb instanceof TLRPC.TL_photoCachedSize) {
                photoSize = message.media.document.thumb;
            }
        }
        //else if (message.media instanceof TLRPC.TL_messageMediaWebPage) {
        //    if (message.media.webpage.photo != null) {
        //        for (TLRPC.PhotoSize size : message.media.webpage.photo.sizes) {
        //            if (size instanceof TLRPC.TL_photoCachedSize) {
        //                photoSize = size;
        //                break;
        //            }
        //        }
        //    }
        //}
        if (photoSize != null && photoSize.bytes != null && photoSize.bytes.length != 0) {
            if (photoSize.location instanceof TLRPC.TL_fileLocationUnavailable) {
                photoSize.location = new TLRPC.TL_fileLocation();
                photoSize.location.volume_id = Integer.MIN_VALUE;
                photoSize.location.dc_id = Integer.MIN_VALUE;
                photoSize.location.local_id = UserConfig.lastLocalId;
                UserConfig.lastLocalId--;
            }
            File file = FileLoader.getPathToAttach(photoSize, true);
            if (!file.exists()) {
                try {
                    RandomAccessFile writeFile = new RandomAccessFile(file, "rws");
                    writeFile.write(photoSize.bytes);
                    writeFile.close();
                } catch (Exception e) {

                }
            }
            TLRPC.TL_photoSize newPhotoSize = new TLRPC.TL_photoSize();
            newPhotoSize.w = photoSize.w;
            newPhotoSize.h = photoSize.h;
            newPhotoSize.location = photoSize.location;
            newPhotoSize.size = photoSize.size;
            newPhotoSize.type = photoSize.type;

            if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                for (int a = 0; a < message.media.photo.sizes.size(); a++) {
                    if (message.media.photo.sizes.get(a) instanceof TLRPC.TL_photoCachedSize) {
                        message.media.photo.sizes.set(a, newPhotoSize);
                        break;
                    }
                }
            } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
                message.media.document.thumb = newPhotoSize;
            }
            //else if (message.media instanceof TLRPC.TL_messageMediaWebPage) {
            //    for (int a = 0; a < message.media.webpage.photo.sizes.size(); a++) {
            //        if (message.media.webpage.photo.sizes.get(a) instanceof TLRPC.TL_photoCachedSize) {
            //            message.media.webpage.photo.sizes.set(a, newPhotoSize);
            //            break;
            //        }
            //    }
            //}
        }
    }
    */

    /*
    public static void saveMessagesThumbs(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            saveMessageThumbs(message);
        }
    }
    */
}
