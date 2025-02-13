package net.dv8tion.jda.api.hooks;

import com.github.rfresh2.SimpleEventBus;
import net.dv8tion.jda.api.events.GenericEvent;

import javax.annotation.Nonnull;

public class SimpleEventBusListener implements EventListener {
    @Nonnull
    private final SimpleEventBus eventBus;

    public SimpleEventBusListener()
    {
        this.eventBus = new SimpleEventBus();
    }

    public SimpleEventBusListener(@Nonnull SimpleEventBus eventBus)
    {
        this.eventBus = eventBus;
    }

    @Nonnull
    public SimpleEventBus eventBus()
    {
        return this.eventBus;
    }

    @Override
    public void onEvent(@Nonnull GenericEvent event)
    {
        eventBus.post(event);
    }
}
