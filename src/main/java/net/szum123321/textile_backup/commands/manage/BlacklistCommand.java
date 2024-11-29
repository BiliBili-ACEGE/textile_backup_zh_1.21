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

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.szum123321.textile_backup.TextileBackup;
import net.szum123321.textile_backup.TextileLogger;
import net.szum123321.textile_backup.config.ConfigHelper;

public class BlacklistCommand {
	private final static TextileLogger log = new TextileLogger(TextileBackup.MOD_NAME);
	private final static ConfigHelper config = ConfigHelper.INSTANCE;

	public static LiteralArgumentBuilder<ServerCommandSource> register() {
		return CommandManager.literal("blacklist")
				.then(CommandManager.literal("add")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(BlacklistCommand::executeAdd)
						)
				).then(CommandManager.literal("remove")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(BlacklistCommand::executeRemove)
						)
				).then(CommandManager.literal("list")
						.executes(ctx -> executeList(ctx.getSource()))
				).executes(ctx -> help(ctx.getSource()));
	}

	private static int help(ServerCommandSource source) {
		Text info = Text.translatable("text.list.info");
		log.sendInfo(source, info.getString());

		return 1;
	}

	private static int executeList(ServerCommandSource source) {
		StringBuilder builder = new StringBuilder();

		builder.append(Text.translatable("text.blacklist.info").getString());

		for(String name : config.get().playerBlacklist){
			builder.append(name);
			builder.append(", ");
		}

		log.sendInfo(source, builder.toString());

		return 1;
	}

	private static int executeAdd(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
		Text info = Text.translatable("text.playerblacklist.info");
		if(config.get().playerBlacklist.contains(player.getNameForScoreboard())) {
			log.sendInfo(ctx.getSource(), info.getString(), player.getNameForScoreboard());
		} else {
			config.get().playerBlacklist.add(player.getNameForScoreboard());
			config.save();

			StringBuilder builder = new StringBuilder();

			builder.append(Text.translatable("text.playerlist.title"));
			builder.append(player.getNameForScoreboard());
			builder.append(Text.translatable("text.addplayer.blacklist.info").getString());

			if(config.get().playerWhitelist.contains(player.getNameForScoreboard())){
				config.get().playerWhitelist.remove(player.getNameForScoreboard());
				config.save();
				builder.append(Text.translatable("text.remove.whitelist.info").getString());
			}

			builder.append(Text.translatable("text.successfully.info").getString());

			ctx.getSource().getServer().getCommandManager().sendCommandTree(player);

			log.sendInfo(ctx.getSource(), builder.toString());
		}

		return 1;
	}

	private static int executeRemove(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
		Text info = Text.translatable("text.addplayer.black.info");
		Text info2 = Text.translatable("text.remove.blacklist.info");
		if(!config.get().playerBlacklist.contains(player.getNameForScoreboard())) {
			log.sendInfo(ctx.getSource(), info.getString(), player.getNameForScoreboard());
		} else {
			config.get().playerBlacklist.remove(player.getNameForScoreboard());
			config.save();

			ctx.getSource().getServer().getCommandManager().sendCommandTree(player);

			log.sendInfo(ctx.getSource(), info2.getString(), player.getNameForScoreboard());
		}

		return 1;
	}
}
