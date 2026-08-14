package com.iptv.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.remote.Http
import com.iptv.player.di.ServiceLocator
import com.iptv.player.sharing.PawnsManager

class IptvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.startupMaintenance()
        IptvAnalytics.init(this)
        // Configures the sharing SDK only. Nothing is shared and no service
        // starts until the viewer accepts the consent dialog — and in a build
        // with no pawns.apiKey this call returns immediately having done
        // nothing at all. See PawnsManager.
        PawnsManager.init(this)
    }

    /**
     * Coil, configured for a TV box rather than for a phone.
     *
     * Left unconfigured, Coil sizes its memory cache at 25% of the app's heap
     * limit. On the 2 GB Android TV box this app was profiled on that is a
     * 256 MB limit, so ~64 MB of bitmaps — on a device that had 138 MB of free
     * system memory to begin with. Channel logos are 48×34 dp; there is no
     * version of this app that needs 64 MB of them.
     *
     * The other three settings each address something specific to IPTV:
     *
     * - **`respectCacheHeaders(false)`** is the one that changes what the user
     *   feels. Provider logo hosts routinely send `no-cache`, or no caching
     *   headers at all, so by default every logo is re-fetched on every cold
     *   start — a few hundred requests to draw a list the device already has
     *   the images for. Artwork is not data that goes stale in a way anyone
     *   would notice, so the disk cache is allowed to just keep it.
     * - **`allowRgb565(true)`** halves the memory of an opaque bitmap. It is a
     *   heuristic Coil only applies when the image has no alpha channel, which
     *   is what makes it safe to turn on globally: transparent PNG logos —
     *   very common, and the case where RGB_565 would put a black box behind
     *   the artwork — keep their alpha and stay ARGB_8888.
     * - **`crossfade(false)`** because a fade per tile is a GPU animation per
     *   tile, and a D-pad list scrolls in discrete jumps where the effect
     *   mostly reads as smearing rather than polish.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.imageClient() }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizeBytes(ARTWORK_MEMORY_CACHE_BYTES)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork"))
                .maxSizeBytes(ARTWORK_DISK_CACHE_BYTES)
                .build()
        }
        .respectCacheHeaders(false)
        .allowRgb565(true)
        .crossfade(false)
        .build()

    private companion object {
        /**
         * Enough for roughly a thousand channel logos or a few hundred posters
         * — comfortably more than any one screen holds, which is all a memory
         * cache has to do. Everything beyond that is the disk cache's job.
         */
        const val ARTWORK_MEMORY_CACHE_BYTES = 24 * 1024 * 1024

        /** Sized so a full playlist's logos survive a reboot without filling a
         *  box whose internal storage is often only 8 GB. */
        const val ARTWORK_DISK_CACHE_BYTES = 64L * 1024 * 1024
    }
}
