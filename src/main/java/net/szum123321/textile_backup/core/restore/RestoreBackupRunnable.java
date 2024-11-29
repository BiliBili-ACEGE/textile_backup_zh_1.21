/*
 * A simple backup mod for Fabric
 * Copyright (C)  2022   Szum123321
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.szum123321.textile_backup.core.restore;

import net.minecraft.text.Text;
import net.szum123321.textile_backup.Globals;
import net.szum123321.textile_backup.TextileBackup;
import net.szum123321.textile_backup.TextileLogger;
import net.szum123321.textile_backup.config.ConfigHelper;
import net.szum123321.textile_backup.config.ConfigPOJO;
import net.szum123321.textile_backup.core.ActionInitiator;
import net.szum123321.textile_backup.core.CompressionStatus;
import net.szum123321.textile_backup.core.Utilities;
import net.szum123321.textile_backup.core.create.ExecutableBackup;
import net.szum123321.textile_backup.core.restore.decompressors.GenericTarDecompressor;
import net.szum123321.textile_backup.core.restore.decompressors.ZipDecompressor;
import net.szum123321.textile_backup.mixin.MinecraftServerSessionAccessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.FutureTask;

/**
 * This class restores a file provided by RestoreContext.
 */
public class RestoreBackupRunnable implements Runnable {
    private final static TextileLogger log = new TextileLogger(TextileBackup.MOD_NAME);
    private final static ConfigHelper config = ConfigHelper.INSTANCE;

    private final RestoreContext ctx;

    public RestoreBackupRunnable(RestoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Text run_info = Text.translatable("text.Shutting.server.info");
        Text run_error_info =Text.translatable("text.unpacking_error.info");
        Globals.INSTANCE.globalShutdownBackupFlag.set(false);

        log.info(run_info.getString());

        ctx.server().stop(false);

        Path worldFile = Utilities.getWorldFolder(ctx.server()),
                tmp;

        try {
            tmp = Files.createTempDirectory(
                    ctx.server().getRunDirectory().toAbsolutePath(),
                    ctx.restoreableFile().getFile().getFileName().toString()
            );
        } catch (IOException e) {
            log.error(run_error_info.getString(), e);
            return;
        }

        //By making a separate thread we can start unpacking an old backup instantly
        //Let the server shut down gracefully, and wait for the old world backup to complete
        FutureTask<Void> waitForShutdown = new FutureTask<>(() -> {
            ctx.server().getThread().join(); //wait for server thread to die and save all its state

            if(config.get().backupOldWorlds) {
                return ExecutableBackup.Builder
                                .newBackupContextBuilder()
                                .setServer(ctx.server())
                                .setInitiator(ActionInitiator.Restore)
                                .noCleanup()
                                .setComment("Old_World" + (ctx.comment() != null ? "_" + ctx.comment() : ""))
                                .announce()
                                .build().call();
            }
            return null;
        });

        //run the thread.
        Text info = Text.translatable("text.Server.shutdown.wait.info");
        new Thread(waitForShutdown, info.getString()).start();

        try {
            Text info2 = Text.translatable("text.starting.decomprseeion.info");
            log.info(info2.getString());

            long hash;

            if (ctx.restoreableFile().getArchiveFormat() == ConfigPOJO.ArchiveFormat.ZIP)
                hash = ZipDecompressor.decompress(ctx.restoreableFile().getFile(), tmp);
            else
                hash = GenericTarDecompressor.decompress(ctx.restoreableFile().getFile(), tmp);
            Text loginfo = Text.translatable("text.waiting.server.info");
            log.info(loginfo.getString());

            //locks until the backup is finished and the server is dead
            waitForShutdown.get();

            Optional<String> errorMsg;
            Text status_error_info = Text.translatable("text.status.error.info");
            Text status_info = Text.translatable("text.status.info");

            if(Files.notExists(CompressionStatus.resolveStatusFilename(tmp))) {
                errorMsg = Optional.of(status_error_info.getString());
            } else {
                CompressionStatus status = CompressionStatus.readFromFile(tmp);

                log.info(status_info.getString(), status);

                Files.delete(tmp.resolve(CompressionStatus.DATA_FILENAME));

                errorMsg = status.validate(hash, ctx);
            }

            if(errorMsg.isEmpty() || !config.get().integrityVerificationMode.verify()) {
                Text back_valid = Text.translatable("text.backup.valid.info");
                Text back_damaged = Text.translatable("text.backup.damaged.info");
                if (errorMsg.isEmpty()) log.info(back_valid.getString());
                else log.info(back_damaged.getString(), errorMsg.get());

                //Disables write lock to override world file
                ((MinecraftServerSessionAccessor) ctx.server()).getSession().close();

                Utilities.deleteDirectory(worldFile);
                Files.move(tmp, worldFile);

                if (config.get().deleteOldBackupAfterRestore) {
                    Text Deleting_restored = Text.translatable("text.restored.Deleting.info");
                    log.info(Deleting_restored.getString());
                    Files.delete(ctx.restoreableFile().getFile());
                }
            } else {
                log.error(errorMsg.get());
            }

        } catch (Exception e) {
            Text log_info = Text.translatable("text.error.restore.info");
            log.error(log_info.getString(), e);
        } finally {
            //Regardless of what happened, we should still clean up
            if(Files.exists(tmp)) {
                try {
                    Utilities.deleteDirectory(tmp);
                } catch (IOException ignored) {}
            }
        }

        //in case we're playing on client
        Globals.INSTANCE.globalShutdownBackupFlag.set(true);
        Text done = Text.translatable("text.done.info");
        log.info(done.getString());
    }
}