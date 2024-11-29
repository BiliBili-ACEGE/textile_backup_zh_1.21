package net.szum123321.textile_backup.core.create;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.szum123321.textile_backup.Globals;
import net.szum123321.textile_backup.TextileBackup;
import net.szum123321.textile_backup.TextileLogger;
import net.szum123321.textile_backup.config.ConfigHelper;
import net.szum123321.textile_backup.core.ActionInitiator;
import net.szum123321.textile_backup.core.Cleanup;
import net.szum123321.textile_backup.core.Utilities;
import net.szum123321.textile_backup.core.WorldSavingState;
import net.szum123321.textile_backup.core.create.compressors.ParallelZipCompressor;
import net.szum123321.textile_backup.core.create.compressors.ZipCompressor;
import net.szum123321.textile_backup.core.create.compressors.tar.AbstractTarArchiver;
import net.szum123321.textile_backup.core.create.compressors.tar.ParallelBZip2Compressor;
import net.szum123321.textile_backup.core.create.compressors.tar.ParallelGzipCompressor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

public record ExecutableBackup(@NotNull MinecraftServer server,
                            ServerCommandSource commandSource,
                            ActionInitiator initiator,
                            boolean save,
                            boolean cleanup,
                            String comment,
                            LocalDateTime startDate) implements Callable<Void> {

    private final static TextileLogger log = new TextileLogger(TextileBackup.MOD_NAME);
    private final static ConfigHelper config = ConfigHelper.INSTANCE;

    public boolean startedByPlayer() {
        return initiator == ActionInitiator.Player;
    }

    public void announce() {
        Text info = Text.translatable("text.warning.info");
        if(config.get().broadcastBackupStart) {
            Utilities.notifyPlayers(server,
                   info.getString()
            );
        } else {
            log.sendInfoAL(this, info.getString());
        }

        StringBuilder builder = new StringBuilder();

        builder.append(Text.translatable("text.backup.started.info").getString());

        builder.append(initiator.getPrefix());

        if(startedByPlayer())
            builder.append(commandSource.getDisplayName().getString());
        else
            builder.append(initiator.getName());

        builder.append(" on: ");
        builder.append(Utilities.getDateTimeFormatter().format(LocalDateTime.now()));

        log.info(builder.toString());
    }
    @Override
    public Void call() throws Exception {
        Text info = Text.translatable("text.outfile.title");
        Text info2 = Text.translatable("text.saving.server.info");
        Text info3 = Text.translatable("text.starting.backup.info");
        Text info4 = Text.translatable("text.world.backup.info");
        Text info5 = Text.translatable("text.running.info");
        Text info6 = Text.translatable("text.parallel.Zip.info");
        Text info7 = Text.translatable("text.regular.Zip.info");
        Text info8 = Text.translatable("text.done.info");
        Text errorfileinfo = Text.translatable("text.error.file.info");
        Text errorwhileinfo = Text.translatable("text.error.while.info");
        Path outFile = Utilities.getBackupRootPath(Utilities.getLevelName(server)).resolve(getFileName());

        log.trace(info.getString(), outFile);

        AtomicReference<Optional<WorldSavingState>> state = new AtomicReference<>(Optional.empty());

        try {
            Globals.INSTANCE.disableWatchdog = true;
            //I think I should synchronise these two next calls...

            //Execute following call on the server executor
            server.submitAndJoin(() -> {
                if (save) { //save the world
                    // We need to flush everything as next thing we'll be copying all the files.
                    // this is mostly the reason for #81 - minecraft doesn't flush during scheduled saves.
                    log.sendInfoAL(this.commandSource, info2.getString());

                    server.saveAll(true, true, false);
                }
                state.set(Optional.of(WorldSavingState.disable(server)));
            });

            Globals.INSTANCE.updateTMPFSFlag(server);

            log.sendInfoAL(this, info3.getString());

            Path world = Utilities.getWorldFolder(server);

            log.trace(info4.getString(), world);

            Files.createDirectories(outFile.getParent());
            Files.createFile(outFile);

            int coreCount;

            if (config.get().compressionCoreCountLimit <= 0) coreCount = Runtime.getRuntime().availableProcessors();
            else
                coreCount = Math.min(config.get().compressionCoreCountLimit, Runtime.getRuntime().availableProcessors());

            log.trace(info5.getString(), coreCount, Runtime.getRuntime().availableProcessors());

            switch (config.get().format) {
                case ZIP -> {
                    if (coreCount > 1 && !Globals.INSTANCE.disableTMPFS()) {
                        log.trace(info6.getString(), coreCount);
                        ParallelZipCompressor.getInstance().createArchive(world, outFile, this, coreCount);
                    } else {
                        log.trace(info7.getString());
                        ZipCompressor.getInstance().createArchive(world, outFile, this, coreCount);
                    }
                }
                case BZIP2 -> ParallelBZip2Compressor.getInstance().createArchive(world, outFile, this, coreCount);
                case GZIP -> ParallelGzipCompressor.getInstance().createArchive(world, outFile, this, coreCount);
                case TAR -> new AbstractTarArchiver().createArchive(world, outFile, this, coreCount);
            }

            if(cleanup) new Cleanup(commandSource, Utilities.getLevelName(server)).call();

            if (config.get().broadcastBackupDone) Utilities.notifyPlayers(server, info8.getString());
            else log.sendInfoAL(this, info8.getString());

        } catch (Throwable e) {
            //ExecutorService swallows exception, so I need to catch everything
            log.error(errorfileinfo.getString(), e);

            if (ConfigHelper.INSTANCE.get().integrityVerificationMode.isStrict()) {
                try {
                    Files.delete(outFile);
                } catch (IOException ex) {
                    log.error(errorwhileinfo.getString(), outFile, ex);
                }
            }

            if (initiator == ActionInitiator.Player)
                log.sendError(this, errorfileinfo.getString());

            throw e;
        } finally {
            if (state.get().isPresent()) {
                state.get().get().enable(server);
            }
            Globals.INSTANCE.disableWatchdog = false;
        }

        return null;
    }

    private String getFileName() {
        return Utilities.getDateTimeFormatter().format(startDate) +
                (comment != null ? "#" + comment.replaceAll("[\\\\/:*?\"<>|#]", "") : "") +
                config.get().format.getCompleteString();
    }
    public static class Builder {
        private MinecraftServer server;
        private ServerCommandSource commandSource;
        private ActionInitiator initiator;
        private boolean save;
        private boolean cleanup;
        private String comment;
        private boolean announce;

        private boolean guessInitiator;

        public Builder() {
            this.server = null;
            this.commandSource = null;
            this.initiator = null;
            this.save = false;
            cleanup = true; //defaults
            this.comment = null;
            this.announce = false;

            guessInitiator = false;
        }

        public static ExecutableBackup.Builder newBackupContextBuilder() {
            return new ExecutableBackup.Builder();
        }

        public ExecutableBackup.Builder setCommandSource(ServerCommandSource commandSource) {
            this.commandSource = commandSource;
            return this;
        }

        public ExecutableBackup.Builder setServer(MinecraftServer server) {
            this.server = server;
            return this;
        }

        public ExecutableBackup.Builder setInitiator(ActionInitiator initiator) {
            this.initiator = initiator;
            return this;
        }

        public ExecutableBackup.Builder setComment(String comment) {
            this.comment = comment;
            return this;
        }

        public ExecutableBackup.Builder guessInitiator() {
            this.guessInitiator = true;
            return this;
        }

        public ExecutableBackup.Builder saveServer() {
            this.save = true;
            return this;
        }

        public ExecutableBackup.Builder noCleanup() {
            this.cleanup = false;
            return this;
        }

        public ExecutableBackup.Builder announce() {
            this.announce = true;
            return this;
        }

        public ExecutableBackup build() {
            Text providedinfo = Text.translatable("text.initiator.provided.info");
            Text providedinfo2 = Text.translatable("text.provided.info");
            if (guessInitiator) {
                initiator = Utilities.wasSentByPlayer(commandSource) ? ActionInitiator.Player : ActionInitiator.ServerConsole;
            } else if (initiator == null) throw new NoSuchElementException(providedinfo.getString());

            if (server == null) {
                if (commandSource != null) setServer(commandSource.getServer());
                else throw new RuntimeException(providedinfo2.getString());
            }

            ExecutableBackup v =  new ExecutableBackup(server, commandSource, initiator, save, cleanup, comment, LocalDateTime.now());

            if(announce) v.announce();

            return v;
        }
    }
}
