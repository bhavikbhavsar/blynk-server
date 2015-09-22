package cc.blynk.server.handlers.hardware.auth;

import cc.blynk.common.enums.Command;
import cc.blynk.common.enums.Response;
import cc.blynk.common.handlers.DefaultExceptionHandler;
import cc.blynk.common.model.messages.ResponseMessage;
import cc.blynk.common.model.messages.protocol.appllication.LoginMessage;
import cc.blynk.common.utils.ServerProperties;
import cc.blynk.server.dao.SessionsHolder;
import cc.blynk.server.dao.UserRegistry;
import cc.blynk.server.exceptions.IllegalCommandException;
import cc.blynk.server.handlers.common.UserNotLoggerHandler;
import cc.blynk.server.handlers.hardware.HardwareHandler;
import cc.blynk.server.model.auth.User;
import cc.blynk.server.storage.StorageDao;
import cc.blynk.server.workers.notifications.NotificationsProcessor;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import static cc.blynk.common.enums.Response.OK;
import static cc.blynk.common.model.messages.MessageFactory.produce;

/**
 * Handler responsible for managing hardware and apps login messages.
 * Initializes netty channel with a state tied with user.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
@ChannelHandler.Sharable
public class HardwareLoginHandler extends SimpleChannelInboundHandler<LoginMessage> implements DefaultExceptionHandler {

    private final UserRegistry userRegistry;
    private final SessionsHolder sessionsHolder;
    private final ServerProperties props;
    private final StorageDao storageDao;
    private final NotificationsProcessor notificationsProcessor;

    public HardwareLoginHandler(ServerProperties props, UserRegistry userRegistry, SessionsHolder sessionsHolder, StorageDao storageDao, NotificationsProcessor notificationsProcessor) {
        this.props = props;
        this.userRegistry = userRegistry;
        this.sessionsHolder = sessionsHolder;
        this.storageDao = storageDao;
        this.notificationsProcessor = notificationsProcessor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LoginMessage message) throws Exception {
        //warn: split may be optimized
        String[] messageParts = message.body.split(" ", 2);

        if (messageParts.length != 1) {
            throw new IllegalCommandException("Wrong income message format.", message.id);
        }

        String token = messageParts[0].trim();
        User user = userRegistry.tokenManager.getUserByToken(token);

        if (user == null) {
            log.debug("HardwareLogic token is invalid. Token '{}', '{}'", token, ctx.channel().remoteAddress());
            ctx.writeAndFlush(new ResponseMessage(message.id, Command.RESPONSE, Response.INVALID_TOKEN));
            return;
        }

        Integer dashId = UserRegistry.getDashIdByToken(user.dashTokens, token, message.id);

        sessionsHolder.addHardwareChannel(user, ctx.channel());

        log.info("{} hardware joined.", user.name);

        ctx.pipeline().remove(this);
        ctx.pipeline().remove(UserNotLoggerHandler.class);
        ctx.pipeline().addLast(new HardwareHandler(props, sessionsHolder, storageDao, notificationsProcessor, new HandlerState(dashId, user, token)));

        ctx.writeAndFlush(produce(message.id, OK));

        //send Pin Mode command in case channel connected to active dashboard with Pin Mode command that
        //was sent previously
        if (dashId.equals(user.profile.activeDashId) && user.profile.pinModeMessage != null) {
            ctx.writeAndFlush(user.profile.pinModeMessage);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
       handleGeneralException(ctx, cause);
    }
}
