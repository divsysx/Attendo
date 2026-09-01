package com.attendo.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

/**
 * The switch between "uninstalling Attendo actually uninstalls it" and "Android may
 * bring the semester back".
 *
 * `android:allowBackup` is fixed at install time, so it cannot be what the "Automatic
 * backup" toggle flips. What Android does support — and documents — is this: an app may
 * declare a backup agent and ask for the file-based Auto Backup path
 * (`android:fullBackupOnly="true"`), and the agent's [onFullBackup] decides what a pass
 * stores. Calling `super` runs the system's default, which applies the rules in
 * `backup_rules.xml` / `data_extraction_rules.xml`; contributing nothing means the pass
 * stores nothing. That is the whole mechanism: the toggle is read at every pass, on this
 * device, and off means no data leaves the phone through Android's backup from then on.
 *
 * Restore is deliberately *not* gated. The agent's decision has to be made in a
 * restricted-mode process at the moment of the pass, and the primary case for restore is
 * a fresh install — where there is no preference to read yet, only the backup itself.
 * So consent lives at backup time: a dataset can only exist if the toggle was on when
 * Android took it, and restoring it honours that. (Turning the toggle off afterwards
 * stops new copies but cannot delete one Android has already stored — no public API
 * can — which is why the settings screen says so in those words.)
 *
 * The two abstract key/value methods are left blank: with
 * `android:fullBackupOnly="true"` they are never called, and the docs' instruction for
 * an agent that does not want key/value backup is exactly that.
 */
class AttendoBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (!AndroidBackupStore.isEnabled(this)) return
        super.onFullBackup(data)
    }

    override fun onBackup(
        oldState: ParcelFileDescriptor,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        // Never called: fullBackupOnly routes every pass to onFullBackup.
    }

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Int,
        newState: ParcelFileDescriptor,
    ) {
        // Never called: fullBackupOnly routes every restore to the full-data path.
    }
}
