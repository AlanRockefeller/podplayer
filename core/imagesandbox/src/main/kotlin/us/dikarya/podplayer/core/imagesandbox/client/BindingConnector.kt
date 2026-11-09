/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox
import us.dikarya.podplayer.core.imagesandbox.sandbox.ImageSandboxService

/**
 * Binds to [ImageSandboxService] by its explicit component (it isn't exported, so only this app can). A
 * binding whose process died is dropped and replaced. Callers bound the wait with their own timeout.
 */
internal class BindingConnector(private val context: Context) : SandboxConnector {
    private val lock = Any()
    private var binding: Binding? = null

    private class Binding : ServiceConnection {
        val ready = CompletableDeferred<IImageSandbox?>()

        @Volatile
        var alive = true

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ready.complete(service?.let { IImageSandbox.Stub.asInterface(it) })
        }

        /** The sandbox process died (crashed or was killed). */
        override fun onServiceDisconnected(name: ComponentName?) {
            alive = false
        }

        override fun onBindingDied(name: ComponentName?) {
            alive = false
            ready.complete(null)
        }

        override fun onNullBinding(name: ComponentName?) {
            alive = false
            ready.complete(null)
        }
    }

    override suspend fun connect(): IImageSandbox? {
        val current = synchronized(lock) {
            binding?.takeIf { it.alive } ?: run {
                binding?.let(::unbind)
                binding = null
                val fresh = Binding()
                if (!bind(fresh)) return null
                fresh.also { binding = it }
            }
        }
        val sandbox = current.ready.await()
        if (sandbox == null || !current.alive || !sandbox.asBinder().isBinderAlive) {
            disconnect()
            return null
        }
        return sandbox
    }

    override fun disconnect() {
        synchronized(lock) {
            binding?.let(::unbind)
            binding = null
        }
    }

    private fun bind(connection: Binding): Boolean {
        val intent = Intent().setComponent(ComponentName(context, ImageSandboxService::class.java))
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        if (!bound) unbind(connection)
        return bound
    }

    private fun unbind(connection: Binding) {
        connection.alive = false
        connection.ready.complete(null)
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Never bound, or already unbound.
        }
    }
}
