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
        final Listeners listeners = main.listeners;
        if (listeners == null) {
            // 起服时配置没读出来，监听器还没建起来
            source.sendMessage(Component.text("Vmessage 还没加载好（config.toml 读不出来），/sendall 用不了",
                    NamedTextColor.RED));
            return;
        }
        // ⚠️ 跟玩家聊天走同一道闸（Message.enabled + server-filter）：
        //    不然 /sendall 能从一个「不参与跨服聊天」的服把话喊到全服，等于绕过配置。
        if (!listeners.canSpeakHere(p)) {
            source.sendMessage(Component.text("这条发不出去：Message.enabled 关了，或你所在的服不在跨服聊天范围里",
                    NamedTextColor.RED));
            return;
        }
        // ⚠️ 以前只取 args[0]：/sendall 大家好 各位 只会发出「大家好」。整句都要带上。
        final String s = String.join(" ", args);
        listeners.message(p, s);
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        final CommandSource source = invocation.source();
        return source instanceof Player && source.hasPermission(PERMISSION);
    }
}
