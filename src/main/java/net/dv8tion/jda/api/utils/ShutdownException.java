package net.dv8tion.jda.api.utils;

import net.dv8tion.jda.internal.utils.ShutdownReason;

import javax.annotation.Nonnull;

public class ShutdownException extends IllegalStateException
{
    @Nonnull
    private final ShutdownReason shutdownReason;

    public ShutdownException(String message)
    {
        this(null, message, null);
    }

    public ShutdownException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public ShutdownException(ShutdownReason reason)
    {
        this(reason, null, null);
    }

    public ShutdownException(ShutdownReason reason, String message)
    {
        this(reason, message, null);
    }

    public ShutdownException(ShutdownReason reason, String message, Throwable cause)
    {
        super(message, cause);
        this.shutdownReason = reason;
    }

    public @Nonnull ShutdownReason getShutdownReason()
    {
        return shutdownReason;
    }
}
