package com.example.chao.photowalldemo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;

/**
 * Created by chao on 2017/5/11.
 */

public class PhotoWallAdapter extends ArrayAdapter<String> {

    private GridView mPhotoWall;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    private int mItemHeight = 0;
    private HashSet<BitmapWorkerTask> taskCollection;

    public PhotoWallAdapter(@NonNull Context context, @LayoutRes int resource, @NonNull String[] objects, GridView gridView) {
        super(context, resource, objects);
        mPhotoWall = gridView;
        taskCollection = new HashSet<BitmapWorkerTask>();

        //内存缓存
        //获取到应用程序最大的可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheSize = maxMemory / 8;

        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            //计算缓存对象的大小
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };


        try {
            //获取图片硬盘缓存路径
            File diskCacheDir = getDiskCacheDir(context, "thumb");
            if(!diskCacheDir.exists()){
                diskCacheDir.mkdirs();
            }
            mDiskLruCache = DiskLruCache.open(diskCacheDir, getAppVersion(context), 1, 10 * 1024 * 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        String url = getItem(position);

        View view;
        if (convertView == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.photo_layout, null);
        } else {
            view = convertView;
        }
        final ImageView imageView = (ImageView) view.findViewById(R.id.photo);
        if (imageView.getLayoutParams().height != mItemHeight) {
            imageView.getLayoutParams().height = mItemHeight;
        }
        // 给ImageView设置一个Tag，保证异步加载图片时不会乱序
        imageView.setTag(url);
        imageView.setImageResource(R.drawable.empty_photo);
        loadBitmaps(imageView, url);
        return view;
    }

    /**
     * 加载bitmap对象。
     * 如果LruCache中有，就加载到imageview中
     * 如果没有则去网络下载
     * @param imageView
     * @param url
     */
    private void loadBitmaps(ImageView imageView, String url) {
        Bitmap bitmap = getBitmapFromMemory(url);
        if(null == bitmap){
            //bitmap对象为null，需要请求网络
            BitmapWorkerTask bitmapWorkerTask = new BitmapWorkerTask();
            taskCollection.add(bitmapWorkerTask);
            bitmapWorkerTask.execute(url);

        }else {
            //bitmap对象不为null，直接加载到imageview中
            if(null != imageView && bitmap != null){
                imageView.setImageBitmap(bitmap);
            }
        }
    }

    /**
     * 根据url从网络获取图片，异步请求
     */
    class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap>{

        private String imageUrl;

        @Override
        protected Bitmap doInBackground(String... params) {
            imageUrl = params[0];
            FileDescriptor fileDescriptor = null;
            FileInputStream fileInputStream = null;
            DiskLruCache.Snapshot snapshot = null;


            try {
                //MD5加密格式的key，方便硬盘存储
                String key = hashKeyForDisk(imageUrl);
                snapshot =mDiskLruCache.get(key);

                if(null == snapshot){
                    //硬盘中没有图片，需要进行网络请求
                    DiskLruCache.Editor editor = mDiskLruCache.edit(key);
                    if(null != editor){
                        OutputStream outputStream = editor.newOutputStream(0);
                        if (downloadUrlToStream(imageUrl, outputStream)) {
                            editor.commit();
                        } else {
                            editor.abort();
                        }
                    }
                    snapshot =mDiskLruCache.get(key);
                }

                if(null != snapshot){
                    //图片已经缓存
                    fileInputStream = (FileInputStream) snapshot.getInputStream(0);
                    fileDescriptor = fileInputStream.getFD();
                }

                // 将缓存数据解析成Bitmap对象
                Bitmap bitmap = null;
                if (fileDescriptor != null) {
                    bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                }
                if (bitmap != null) {
                    // 将Bitmap对象添加到内存缓存当中
                    addBitmapToMemoryCache(params[0], bitmap);
                }
                return bitmap;


            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fileDescriptor == null && fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    } catch (IOException e) {
                    }
                }
            }
            return null;
        }



        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            // 根据Tag找到相应的ImageView控件，将下载好的图片显示出来。
            ImageView imageView = (ImageView) mPhotoWall.findViewWithTag(imageUrl);
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
            taskCollection.remove(this);
        }
    }



    /**
     * 建立HTTP请求，并获取Bitmap对象。
     *
     * @param urlString
     *            图片的URL地址
     * @return 解析后的Bitmap对象
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), 8 * 1024);
            out = new BufferedOutputStream(outputStream, 8 * 1024);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 把imageurl转化成MD5之后的格式
     * @param imageUrl
     */
    private String hashKeyForDisk(String imageUrl) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(imageUrl.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(imageUrl.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 根据url从内存中获取bitmap对象
     * @param url
     * @return
     */
    private Bitmap getBitmapFromMemory(String url) {
        return mMemoryCache.get(url);
    }

    /**
     * 把图片存放到内存缓存中去
     * @param param
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String param, Bitmap bitmap) {
        if(mMemoryCache.get(param) == null){
            mMemoryCache.put(param, bitmap);
        }
    }

    /**
     * 获取到APP的版本号
     * @return
     * @param context
     */
    private int getAppVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    0);
            return info.versionCode;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 1;
    }

    /**
     * 根据传入的名称获取硬盘缓存地址
     *  @param context
     * @param thumb
     */
    private File getDiskCacheDir(Context context, String thumb) {
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !Environment.isExternalStorageRemovable()) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + thumb);
    }

    /**
     * 将缓存记录同步到journal文件中。
     */
    public void fluchCache() {
        if (mDiskLruCache != null) {
            try {
                mDiskLruCache.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 取消所有正在下载或等待下载的任务。
     */
    public void cancelAllTasks() {
        if (taskCollection != null) {
            for (BitmapWorkerTask task : taskCollection) {
                task.cancel(false);
            }
        }
    }

    /**
     * 设置item子项的高度。
     */
    public void setItemHeight(int height) {
        if (height == mItemHeight) {
            return;
        }
        mItemHeight = height;
        notifyDataSetChanged();
    }
}
