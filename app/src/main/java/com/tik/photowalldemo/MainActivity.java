package com.tik.photowalldemo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.ViewTreeObserver;
import android.widget.GridView;

/**
 *  Android照片墙完整版，完美结合LruCache和DiskLruCache
 * 原文地址：http://blog.csdn.net/guolin_blog/article/details/34093441
 */
public class MainActivity extends AppCompatActivity {

    private GridView mPhotoWall;

    private PhotoWallAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final int imageSpacing = getResources().getDimensionPixelSize(R.dimen.image_thumbnail_spacing);
        mPhotoWall = (GridView) findViewById(R.id.photo_wall);
        mAdapter = new PhotoWallAdapter(this, 0, Images.imageThumbUrls, mPhotoWall);
        mPhotoWall.setAdapter(mAdapter);
        mPhotoWall.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int numColumns = mPhotoWall.getNumColumns();
                int columnWidth = mPhotoWall.getWidth()/numColumns - imageSpacing;
                mAdapter.setItemHeight(columnWidth);
                mPhotoWall.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            }
        });

    }

    @Override
    protected void onPause() {
        super.onPause();
        mAdapter.flushCache();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出程序时结束所有的下载任务
        mAdapter.cancelAllTasks();
    }
}
