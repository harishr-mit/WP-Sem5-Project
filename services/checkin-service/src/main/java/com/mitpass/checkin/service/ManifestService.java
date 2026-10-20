package com.mitpass.checkin.service;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.web.dto.ScanDtos.ManifestPass;
import com.mitpass.checkin.web.dto.ScanDtos.ManifestResponse;
import com.mitpass.checkin.web.dto.ScanDtos.Window;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What a scanner phone keeps to check passes while offline (docs/plans/phase-7.md §7): the event's sessions and the
 * rules the pipeline applies, and every pass of the event with its status. The status follows pass-service's
 * revocations through this service's copy (reconciled every minute, D14), so a pass revoked before the phone went
 * offline is refused offline too. With the public pass keys, that is all the browser needs.
 */
@Service
public class ManifestService {

    private final JdbcClient jdbc;
    private final EventCatalog catalog;
    private final CheckinProperties properties;

    public ManifestService(JdbcClient jdbc, EventCatalog catalog, CheckinProperties properties) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.properties = properties;
    }

    public ManifestResponse manifest(long eventId) {
        EventInfo event = catalog.event(eventId);
        List<Window> sessions = event.sessions().isEmpty()
                ? List.of(new Window(0, event.startsAt(), event.endsAt()))
                : event.sessions().stream()
                        .map(s -> new Window(s.id(), s.startsAt(), s.endsAt()))
                        .toList();
        List<ManifestPass> passes = jdbc.sql("""
                        SELECT pass_id, pass_number, holder_name, status FROM pass_ref
                        WHERE event_id = :event AND pass_number IS NOT NULL ORDER BY pass_number
                        """)
                .param("event", eventId)
                .query((rs, row) -> new ManifestPass(
                        rs.getObject("pass_id", UUID.class),
                        rs.getString("pass_number"),
                        LiveStats.firstName(rs.getString("holder_name")),
                        rs.getString("status")))
                .list();
        return new ManifestResponse(
                eventId,
                event.code(),
                properties.passIssuer(),
                properties.tokenClockSkew().toSeconds(),
                properties.doorsOpenBeforeSession().toSeconds(),
                sessions,
                passes);
    }
}
