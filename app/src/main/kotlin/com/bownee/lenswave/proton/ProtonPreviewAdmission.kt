package com.bownee.lenswave.proton

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether previews may still be downloaded right now, asked between the chunks of a preview
 * batch. The worker decides once per batch whether previews are allowed (charger or screen),
 * but a batch of sixteen previews outlives a momentary glance at the app; with the gate the
 * chunks that have not started yet are abandoned as soon as the answer turns to no, and only
 * the passes already under way finish. Without a bound gate (no run in progress) the answer
 * is yes: nothing else downloads previews in the background.
 */
@Singleton
internal class ProtonPreviewAdmission
    @Inject
    constructor() {
        private val gate = AtomicReference<(() -> Boolean)?>(null)

        fun previewsAllowed(): Boolean = gate.get()?.invoke() ?: true

        /** Installs the run's answer; [unbind] when the run ends so the default applies again. */
        fun bind(previewsAllowed: () -> Boolean) {
            gate.set(previewsAllowed)
        }

        fun unbind() {
            gate.set(null)
        }
    }
