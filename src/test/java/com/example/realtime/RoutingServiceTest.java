package com.example.realtime;

import com.example.realtime.model.ChangeEvent;
import com.example.realtime.model.ChangeType;
import com.example.realtime.model.EventSource;
import com.example.realtime.repository.RoutingRuleRepository;
import com.example.realtime.service.RoutingService;
import com.example.realtime.service.VendorClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutingServiceTest {

    @Mock
    RoutingRuleRepository repository;

    @Mock
    VendorClient vendorClient;

    @InjectMocks
    RoutingService routingService;

    @Test
    void routesWithDefaultWhenNoRule() {
        // When no database rule exists, the service should fall back to the "default" route
        ChangeEvent event = new ChangeEvent("1", ChangeType.CREATE, EventSource.CSV, Map.of());
        when(repository.findById("1")).thenReturn(Optional.empty());

        routingService.route(event);

        verify(vendorClient).send(event, "default");
    }
}
