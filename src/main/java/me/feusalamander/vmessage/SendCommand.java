package me.feusalamander.vmessage;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** /sendall —— 把一句话广播到所有服（走的是聊天那套格式）。 */
public final class SendCommand implements SimpleCommand {
    /** 谁能用；⚠️ 以前这里只判「是玩家」，等于所有玩家都能全服喊话。 */
    private static final String PERMISSION = "vmessage.sendall";
    private final VMessage main;

    SendCommand(VMessage main) {
        this.main = main;
    }

    @Override
    public void execute(final Invocation invocation) {
        if(!hasPermission(invocation)){
            return;
        }
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        if (args.length == 0) {
            source.sendMessage(Component.text("用法：/sendall 要说的话", NamedTextColor.RED));
            return;
        }
        final Player p = (Player) source;
        // ⚠️ 以前只取 args[0]：/sendall 大家好 各位 只会发出「大家好」。整句都要带上。
        final String s = String.join(" ", args);
        main.listeners.message(p, s);
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        final CommandSource source = invocation.source();
        return source instanceof Player && source.hasPermission(PERMISSION);
    }
}
