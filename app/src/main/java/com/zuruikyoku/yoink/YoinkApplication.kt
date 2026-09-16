package com.zuruikyoku.yoink

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.zuruikyoku.yoink.data.download.DownloadNotifier

class YoinkApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.ensureChannel(this)
    }

    // Registers video-frame decoding so history thumbnails for downloaded videos/GIFs
    // render a frame instead of a blank/broken image.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
