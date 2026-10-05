package me.feusalamander.vmessage;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;

public final class ReloadCommand implements SimpleCommand {
    private final VMessage main;

    ReloadCommand(VMessage main) {
        this.main = main;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        // 只有一个子命令，少打一个单词也算数（/vmessage = /vmessage reload）
        if (args.length > 0 && !args[0].equalsIgnoreCase("reload")) {
            source.sendMessage(Component.text("Usage: /vmessage reload", NamedTextColor.RED));
            return;
        }
        if (main.reload()) {
            source.sendMessage(Component.text("Vmessage 配置已重载（改 config.toml 后不用重启）",
                    NamedTextColor.GREEN));
            return;
        }
        final String reason = main.lastReloadError();
        source.sendMessage(Component.text("config.toml 读不出来，已保留旧配置："
                + (reason == null ? "未知原因" : reason), NamedTextColor.RED));
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission("vmessage.reload");
    }

    private static final List<String> suggestion = List.of("reload");

    @Override
    public List<String> suggest(final Invocation invocation) {
        final String[] args = invocation.arguments();
        // ⚠️ 大小写敏感的话敲 "/vmessage RELOAD" 就补不出候选了，统一按小写比
        if (args.length == 0
                || (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT)))) {
            return suggestion;
        }
        return List.of();
    }
}
