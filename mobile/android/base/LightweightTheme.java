/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.gfx.BitmapUtils;
import org.mozilla.gecko.util.GeckoEventListener;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Application;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;

import java.net.URL;
import java.net.URLConnection;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;

import android.util.Log;

public class LightweightTheme implements GeckoEventListener {
    private static final String LOGTAG = "GeckoLightweightTheme";

    private Application mApplication;
    private Bitmap mBitmap;
    private int mColor;

    public static interface OnChangeListener {
        // This is the View's default post.
        // This is required to post the change/rest on UI thread.
        public boolean post(Runnable action);

        // The View should change its background/text color. 
        public void onLightweightThemeChanged();

        // The View should reset to its default background/text color.
        public void onLightweightThemeReset();
    }

    private List<OnChangeListener> mListeners;
    
    public LightweightTheme(Application application) {
        mApplication = application;
        mListeners = new ArrayList<OnChangeListener>();

        GeckoAppShell.getEventDispatcher().registerEventListener("LightweightTheme:Update", this);
        GeckoAppShell.getEventDispatcher().registerEventListener("LightweightTheme:Disable", this);
    }

    public void addListener(final OnChangeListener listener) {
        // Don't inform the listeners that attached late.
        // Their onLayout() will take care of them before their onDraw();
        mListeners.add(listener);
    }

    public void removeListener(OnChangeListener listener) {
        mListeners.remove(listener);
    }

    public void setLightweightTheme(String headerURL) {
        try {
            // Wait till gecko downloads and gives us the file, don't download.
            if (headerURL.indexOf("http") != -1)
                return;

            // Get the image and convert it to a bitmap.
            URL url = new URL(headerURL);
            InputStream stream = url.openStream();
            mBitmap = BitmapFactory.decodeStream(stream);
            stream.close();

            // To find the dominant color only once, take the bottom 25% of pixels.
            DisplayMetrics dm = mApplication.getResources().getDisplayMetrics();
            int maxWidth = Math.max(dm.widthPixels, dm.heightPixels);
            int height = (int) (mBitmap.getHeight() * 0.25);
            Bitmap cropped = Bitmap.createBitmap(mBitmap, mBitmap.getWidth() - maxWidth,
                                                          mBitmap.getHeight() - height, 
                                                          maxWidth, height);
            mColor = BitmapUtils.getDominantColor(cropped, false);
            cropped.recycle();

            notifyListeners();
        } catch(java.net.MalformedURLException e) {
            mBitmap = null;
        } catch(java.io.IOException e) {
            mBitmap = null;
        }
    }

    public void resetLightweightTheme() {
        // Reset the bitmap.
        mBitmap = null;

        // Post the reset on the UI thread.
        for (OnChangeListener listener : mListeners) {
             final OnChangeListener oneListener = listener;
             oneListener.post(new Runnable() {
                 @Override
                 public void run() {
                     oneListener.onLightweightThemeReset();
                 }
             });
        }
    }

    public void notifyListeners() {
        if (mBitmap == null)
            return;

        // Post the change on the UI thread.
        for (OnChangeListener listener : mListeners) {
             final OnChangeListener oneListener = listener;
             oneListener.post(new Runnable() {
                 @Override
                 public void run() {
                     oneListener.onLightweightThemeChanged();
                 }
             });
        }
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        try {
            if (event.equals("LightweightTheme:Update")) {
                JSONObject lightweightTheme = message.getJSONObject("data");
                String headerURL = lightweightTheme.getString("headerURL"); 
                int mark = headerURL.indexOf('?');
                if (mark != -1)
                    headerURL = headerURL.substring(0, mark);
                setLightweightTheme(headerURL);
            } else if (event.equals("LightweightTheme:Disable")) {
                resetLightweightTheme();
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Exception handling message \"" + event + "\":", e);
        }
    }

    /**
     * Crop the image based on the position of the view on the window.
     * Either the View or one of its ancestors might have scrolled or translated.
     * This value should be taken into account while mapping the View to the Bitmap.
     *
     * @param view The view requesting a cropped bitmap.
     */
    private Bitmap getCroppedBitmap(View view) {
        if (mBitmap == null || view == null)
            return null;

        // Get the global position of the view on the entire screen.
        Rect rect = new Rect();
        view.getGlobalVisibleRect(rect);

        // Get the activity's window position. This does an IPC call, may be expensive.
        Rect window = new Rect();
        view.getWindowVisibleDisplayFrame(window);

        // Calculate the coordinates for the cropped bitmap.
        int screenWidth = view.getContext().getResources().getDisplayMetrics().widthPixels;
        int left = mBitmap.getWidth() - screenWidth + rect.left;
        int right = mBitmap.getWidth() - screenWidth + rect.right;
        int top = rect.top - window.top;
        int bottom = rect.bottom - window.top;

        int offsetX = 0;
        int offsetY = 0;

        // Find if this view or any of its ancestors has been translated or scrolled.
        ViewParent parent;
        View curView = view;
        do {
            if (Build.VERSION.SDK_INT >= 11) {
                offsetX += (int) curView.getTranslationX() - curView.getScrollX();
                offsetY += (int) curView.getTranslationY() - curView.getScrollY();
            } else {
                offsetX -= curView.getScrollX();
                offsetY -= curView.getScrollY();
            }

            parent = curView.getParent();

            if (parent instanceof View)
                curView = (View) parent;

        } while(parent instanceof View && parent != null);

        // Adjust the coordinates for the offset.
        left -= offsetX;
        right -= offsetX;
        top -= offsetY;
        bottom -= offsetY;

        // The either the required height may be less than the available image height or more than it.
        // If the height required is more, crop only the available portion on the image.
        int width = right - left;
        int height = (bottom > mBitmap.getHeight() ? mBitmap.getHeight() - top : bottom - top);

        // There is a chance that the view is not visible or doesn't fall within the phone's size.
        // In this case, 'rect' will have all values as '0'. Hence 'top' and 'bottom' may be negative,
        // and createBitmap() will fail.
        // The view will get a background in its next layout pass.
        try {
            return Bitmap.createBitmap(mBitmap, left, top, width, height);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts the cropped bitmap to a BitmapDrawable and returns the same.
     *
     * @param view The view for which a background drawable is required.
     * @return Either the cropped bitmap as a Drawable or null.
     */
    public Drawable getDrawable(View view) {
        Bitmap bitmap = getCroppedBitmap(view);
        if (bitmap == null)
            return null;

        BitmapDrawable drawable = new BitmapDrawable(view.getContext().getResources(), bitmap);
        drawable.setGravity(Gravity.TOP|Gravity.RIGHT);
        drawable.setTileModeXY(Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        return drawable;
    }

    /**
     * Converts the cropped bitmap to a LightweightThemeDrawable, with the required alpha.
     * LightweightThemeDrawable is optionally placed over a ColorDrawable (of dominant color),
     * if the cropped bitmap cannot fill the entire view.
     *
     * @param view The view for which a background drawable is required.
     * @param alpha The alpha (0..255) value to be applied to the Drawable.
     * @return Either the cropped bitmap as a Drawable or null.
     */
    public Drawable getDrawableWithAlpha(View view, int alpha) {
        return getDrawableWithAlpha(view, alpha, alpha);
    }

    /**
     * Converts the cropped bitmap to a LightweightThemeDrawable, with the required alpha applied as 
     * a LinearGradient. LightweightThemeDrawable is optionally placed over a ColorDrawable 
     * (of dominant color), if the cropped bitmap cannot fill the entire view.
     *
     * @param view The view for which a background drawable is required.
     * @param startAlpha The top alpha (0..255) of the linear gradient to be applied to the Drawable.
     * @param endAlpha The bottom alpha (0..255) of the linear gradient to be applied to the Drawable.
     * @return Either the cropped bitmap as a Drawable or null.
     */
    public Drawable getDrawableWithAlpha(View view, int startAlpha, int endAlpha) {
        Bitmap bitmap = getCroppedBitmap(view);
        if (bitmap == null)
            return null;

        LightweightThemeDrawable drawable = new LightweightThemeDrawable(view.getContext().getResources(), bitmap);
        drawable.setAlpha(startAlpha, endAlpha);
        drawable.setGravity(Gravity.TOP|Gravity.RIGHT|Gravity.FILL_HORIZONTAL);

        if (bitmap.getHeight() != view.getHeight()) {
            ColorDrawable colorDrawable = new ColorDrawable(mColor);
            LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{ colorDrawable, drawable });
            return layerDrawable;
        } else {
            return drawable;
        }
    }
}
