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

package net.szum123321.textile_backup.commands.manage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.szum123321.textile_backup.Globals;
import net.szum123321.textile_backup.TextileBackup;
import net.szum123321.textile_backup.TextileLogger;
import net.szum123321.textile_backup.commands.CommandExceptions;
import net.szum123321.textile_backup.commands.FileSuggestionProvider;
import net.szum123321.textile_backup.core.RestoreableFile;
import net.szum123321.textile_backup.core.Utilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public class DeleteCommand {
    private final static TextileLogger log = new TextileLogger(TextileBackup.MOD_NAME);

    public static LiteralArgumentBuilder<ServerCommandSource> register() {
        return CommandManager.literal("delete")
                .then(CommandManager.argument("file", StringArgumentType.word())
                        .suggests(FileSuggestionProvider.Instance())
                        .executes(ctx -> execute(ctx.getSource(), StringArgumentType.getString(ctx, "file")))
                );
    }

    private static int execute(ServerCommandSource source, String fileName) throws CommandSyntaxException {
        LocalDateTime dateTime;
        Text info = Text.translatable("text.delete.error.info");
        Text info2 = Text.translatable("text.delete.success.info");
        Text info3 = Text.translatable("text.deleted.player.success.info");
        Text info4 = Text.translatable("text.deleted.player.error.info");
        Text info5 = Text.translatable("text.deleted.file.error.info");
        Text info6 = Text.translatable("text.stop.help.info");
        Text info7 = Text.translatable("text.filename.find.error.info");
        Text info8 = Text.translatable("text.try.help.info");
        try {
            dateTime = LocalDateTime.from(Globals.defaultDateTimeFormatter.parse(fileName));
        } catch (DateTimeParseException e) {
            throw CommandExceptions.DATE_TIME_PARSE_COMMAND_EXCEPTION_TYPE.create(e);
        }

        Path root = Utilities.getBackupRootPath(Utilities.getLevelName(source.getServer()));

        RestoreableFile.applyOnFiles(root, Optional.empty(),
                e -> log.sendErrorAL(source, info.getString(), e),
                stream -> stream.filter(f -> f.getCreationTime().equals(dateTime)).map(RestoreableFile::getFile).findFirst()
                ).ifPresentOrElse(file -> {
                    if(Globals.INSTANCE.getLockedFile().filter(p -> p == file).isEmpty()) {
                        try {
                            Files.delete((Path) file);
                            log.sendInfo(source, info2.getString(), file);

                            if(Utilities.wasSentByPlayer(source))
                                log.info(info3.getString(), source.getPlayer().getName(), file);
                        } catch (IOException e) {
                            log.sendError(source, info4.getString());
                        }
                    } else {
                        log.sendError(source, info5.getString());
                        log.sendHint(source, info6.getString());
                    }
                }, () -> {
                    log.sendInfo(source, info7.getString());
                    log.sendInfo(source, info8.getString());
                }
        );
        return 0;
    }
}
