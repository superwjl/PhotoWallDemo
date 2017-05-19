package com.tik.photowalldemo;


import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.NonNull;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

public class PhotoWallAdapter extends ArrayAdapter<String> {

    /**
     * GridView的实例
     */
    private GridView mPhotoWall;
    /**
     * 记录所有正在下载或等待下载的任务。
     */
    private Set<BitmapWorkerTask> taskCollection;
    /**
     * 图片内存缓存技术的核心类，用于缓存所有下载好的图片，在程序内存达到设定值时会将最少最近使用的图片移除掉。
     */
    private LruCache<String, Bitmap> mMemoryCache;
    /**
     * 图片硬盘缓存核心类。
     */
    private DiskLruCache mDiskLruCache;
    /**
     * 记录每个子项的高度。
     */
    private int mItemHeight = 0;

    public PhotoWallAdapter(Context context, int resource, String[] objects, GridView photoWall) {
        super(context, resource, objects);
        mPhotoWall = photoWall;
        taskCollection = new HashSet<>();
        // 获取应用程序最大可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        // 设置图片缓存大小为程序最大可用内存的1/8
        int cacheSize = maxMemory / 8;
        // 初始化LruCache实例
        mMemoryCache = new LruCache<String,Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        // 初始化DiskLruCache实例
        try {
            // 获取图片缓存路径
            File cacheDir = getDiskCacheDir(context, "temp");
            if(!cacheDir.exists()){
                cacheDir.mkdirs();
            }
            // 创建DiskLruCache实例，初始化缓存数据
            mDiskLruCache = DiskLruCache.open(cacheDir, getAppVersion(context), 1, 10 * 1024 * 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 根据传入的uniqueName获取硬盘缓存的路径地址。
     */
    public File getDiskCacheDir(Context context, String uniqueName){
        String cachePath = null;
        if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !Environment.isExternalStorageRemovable()){
            cachePath = context.getExternalCacheDir().getPath();
        }else{
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 获取当前应用程序的版本号。
     */
    public int getAppVersion(Context context){
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return 1;
    }

    public void cancelAllTasks(){}{
        if(taskCollection != null){
            for (BitmapWorkerTask task : taskCollection){
                task.cancel(false);
            }
        }
    }

    /**
     * 使用MD5算法对传入的key进行加密并返回。
     */
    public String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
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
     * 将缓存记录同步到journal文件中。
     */
    public void flushCache(){
        if(mDiskLruCache != null){
            try {
                mDiskLruCache.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        String url = getItem(position);
        ViewHolder holder = null;
        if(convertView == null){
            holder = new ViewHolder();
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.photo_layout, null);
            holder.iv = (ImageView) convertView.findViewById(R.id.photo);
            convertView.setTag(holder);
        }else{
            holder = (ViewHolder) convertView.getTag();
        }
        // 给ImageView设置一个Tag，保证异步加载图片时不会乱序
        if(holder.iv.getHeight() != mItemHeight){
            holder.iv.getLayoutParams().height = mItemHeight;
        }
        /* 为imageview设置tag，防止乱序 */
        holder.iv.setTag(url);
        holder.iv.setImageResource(R.drawable.empty_photo);
        loadBitmap(holder.iv, url);
        return convertView;
    }

    private static class ViewHolder{
        ImageView iv;
    }

    /**
     * 加载Bitmap对象。此方法会在LruCache中检查所有屏幕中可见的ImageView的Bitmap对象，
     * 如果发现任何一个ImageView的Bitmap对象不在缓存中，就会开启异步线程去下载图片。
     */
    public void loadBitmap(ImageView iv, String url){
        Bitmap bitmap = getBitmapFromMemoryCache(url);
        if(bitmap == null){
            BitmapWorkerTask task = new BitmapWorkerTask();
            taskCollection.add(task);
            task.execute(url);
        }else{
            if(iv != null){
                iv.setImageBitmap(bitmap);
            }
        }
    }

    /**
     * 从LruCache中获取键值为key获取bitmap对象
     * @param key
     * @return
     */
    public Bitmap getBitmapFromMemoryCache(String key){
        return mMemoryCache.get(key);
    }

    /**
     * 将图片保存到LruCache里
     * @param key 图片url的hashcode
     * @param bitmap 图片的bitmap对象
     */
    public void addBitmapToMemoryCache(String key, Bitmap bitmap){
        mMemoryCache.put(key, bitmap);
    }

    public void setItemHeight(int height){
        if(mItemHeight == height){
            return;
        }
        mItemHeight = height;
        notifyDataSetChanged();
    }


    /**
     * 异步下载图片的任务。
     */
    class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap>{
        /**
         * 图片的URL地址
         */
        private String imageUrl;

        @Override
        protected Bitmap doInBackground(String... strings) {
            imageUrl = strings[0];
            FileDescriptor fileDescriptor = null;
            FileInputStream fileInputStream = null;
            DiskLruCache.Snapshot snapshot = null;
            // 生成图片URL对应的key
            String key = hashKeyForDisk(imageUrl);
            try {
                // 查找key对应的缓存
                snapshot = mDiskLruCache.get(key);
                if(snapshot == null){
                    // 如果没有找到对应的缓存，则准备从网络上请求数据，并写入缓存
                    DiskLruCache.Editor editor = mDiskLruCache.edit(key);
                    if(editor != null){
                        OutputStream outputStream = editor.newOutputStream(0);
                        if(downloadUrlToStream(imageUrl, outputStream)){
                            editor.commit();
                        }else{
                            editor.abort();
                        }
                    }
                    // 缓存被写入后，再次查找key对应的缓存
                    snapshot = mDiskLruCache.get(key);
                }
                if(snapshot != null){
                    fileInputStream = (FileInputStream) snapshot.getInputStream(0);
                    fileDescriptor = fileInputStream.getFD();
                }
                // 将缓存数据解析成Bitmap对象
                Bitmap bitmap = null;
                if(fileDescriptor != null){
                    bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                }
                if(bitmap != null){
                    // 将Bitmap对象添加到内存缓存当中
                    addBitmapToMemoryCache(imageUrl, bitmap);
                }
                return bitmap;
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if(fileDescriptor != null && fileInputStream != null){
                    try {
                        fileInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            // 根据Tag找到相应的ImageView控件，将下载好的图片显示出来。
            ImageView iv = (ImageView) mPhotoWall.findViewWithTag(imageUrl);
            if(iv != null && bitmap != null){
                iv.setImageBitmap(bitmap);
            }
            taskCollection.remove(this);
        }

        /**
         * 建立HTTP请求，并获取Bitmap对象。
         * @param imageUrl
         * @param outputStream
         * @return
         */
        private boolean downloadUrlToStream(String imageUrl, OutputStream outputStream){
            HttpURLConnection connection = null;
            BufferedInputStream in = null;
            BufferedOutputStream out = null;
            try {
                URL url = new URL(imageUrl);
                connection = (HttpURLConnection) url.openConnection();
                in = new BufferedInputStream(connection.getInputStream(), 8 * 1024);
                out = new BufferedOutputStream(outputStream, 8 * 1024);
                int b;
                while ((b = in.read()) != -1){
                    out.write(b);
                }
                return true;
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if(connection != null){
                    connection.disconnect();
                }
                try {
                    if(in != null){
                        in.close();
                    }
                    if(out != null){
                        out.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }
    }
}
