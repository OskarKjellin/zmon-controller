package org.zalando.zmon.service.impl;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.primitives.Longs;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zalando.zmon.config.EventLogProperties;
import org.zalando.zmon.domain.*;
import org.zalando.zmon.event.Event;
import org.zalando.zmon.event.EventlogEvent;
import org.zalando.zmon.event.ZMonEventType;
import org.zalando.zmon.persistence.AlertDefinitionSProcService;
import org.zalando.zmon.persistence.CheckDefinitionSProcService;
import org.zalando.zmon.service.HistoryService;
import org.zalando.zmon.util.HistoryUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class HistoryServiceImpl implements HistoryService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;

    private final static List<Event> EMPTY_LIST = new ArrayList<>(0);

    private static final Comparator<Activity> ACTIVITY_TIME_COMPARATOR = (o1, o2) -> Longs.compare(o2.getTime(), o1.getTime());

    private final static Logger LOG = LoggerFactory.getLogger(HistoryServiceImpl.class);

    @Autowired
    private CheckDefinitionSProcService checkDefinitionSProc;

    @Autowired
    private AlertDefinitionSProcService alertDefinitionSProc;

    @Autowired
    private EventLogProperties eventLogProperties;

    private ObjectMapper mapper = new ObjectMapper();

    public Event convert(EventlogEvent in) {
        Event e = new Event();
        e.setTypeName(in.getTypeName());
        e.setTime(in.getTime());
        e.setFlowId(in.getFlowId());
        e.setTypeId(in.getTypeId());

        for(Map.Entry<String, JsonNode> ie : in.getAttributes().entrySet()) {
            try {
                e.setAttribute(ie.getKey(), mapper.writeValueAsString(ie.getValue()));
            }
            catch(JsonProcessingException ex) {

            }
        }

        return e;
    }

    @Override
    public List<Activity> getHistory(final int alertDefinitionId, final Integer limit, final Long from, final Long to) {
        final Integer realLimit = resolveLimit(limit, from, to);

        final Long fromMillis = from == null ? null : from * 1000;
        final Long toMillis = to == null ? null : to * 1000;

        List<Activity> history = Collections.emptyList();

        final List<AlertDefinition> definitions = alertDefinitionSProc.getAlertDefinitions(null,
                Collections.singletonList(alertDefinitionId));

        if (!definitions.isEmpty()) {
            final Executor executor = Executor.newInstance();

            final String eventLogService = eventLogProperties.getUrl().toString();

            List<Event> eventsByAlertId = EMPTY_LIST;
            List<Event> eventsByCheckId = EMPTY_LIST;

            String baseQuery = "?";
            if (limit != null) {
                baseQuery += "&limit=" + realLimit;
            }
            if (fromMillis != null) {
                baseQuery += "&from=" + fromMillis;
            }
            if (toMillis != null) {
                baseQuery += "&to=" + toMillis;
            }

            try {
                String query = baseQuery + "&types=212993,212994,212995,212996,212997,212998,213252,213253,213504,213505,213506,213514,213515,213520&key=alertId&value=" + alertDefinitionId;
                final String r = executor.execute(Request.Get(eventLogService + query)).returnContent().asString();
                List<EventlogEvent> tempEvents = mapper.readValue(r, new TypeReference<List<EventlogEvent>>() {
                });

                eventsByAlertId = tempEvents.stream().map(x->convert(x)).collect(Collectors.toList());
            } catch (IOException e) {
                LOG.error("Failed to load events by alertId from {}", eventLogService, e);
            }

            try {
                String query = baseQuery + "&types=213254,213255,213256,213257&key=checkId&value=" + definitions.get(0).getCheckDefinitionId();
                final String r = executor.execute(Request.Get(eventLogService + query)).returnContent().asString();
                eventsByCheckId = mapper.readValue(r, new TypeReference<List<Event>>() {
                });
            } catch (IOException e) {
                LOG.error("Failed to load events by checkId {}", eventLogService, e);
            }

            history = mergeEvents(realLimit, eventsByCheckId, eventsByAlertId);
        }

        return history;
    }

    private Activity createActivity(final Event event) {
        final Activity activity = new Activity();
        activity.setTime(dateToSeconds(event.getTime()));
        activity.setTypeId(event.getTypeId());
        activity.setTypeName(event.getTypeName());
        activity.setAttributes(event.getAttributes());
        return activity;
    }

    private List<Activity> mergeEvents(final Integer limit, final List<Event> eventsByCheckId,
                                       final List<Event> eventsByAlertId) {

        List<Activity> history = Collections.emptyList();

        final int size = (eventsByCheckId == null ? 0 : eventsByCheckId.size())
                + (eventsByAlertId == null ? 0 : eventsByAlertId.size());

        if (size > 0) {
            final List<Activity> activities = new ArrayList<>(size);
            if (eventsByCheckId != null && !eventsByCheckId.isEmpty()) {
                for (final Event event : eventsByCheckId) {
                    activities.add(createActivity(event));
                }
            }

            if (eventsByAlertId != null && !eventsByAlertId.isEmpty()) {
                for (final Event event : eventsByAlertId) {
                    activities.add(createActivity(event));
                }
            }

            Collections.sort(activities, ACTIVITY_TIME_COMPARATOR);
            history = limit == null ? activities : activities.subList(0, limit);
        }

        return history;
    }

    @Override
    public List<ActivityDiff> getCheckDefinitionHistory(final int checkDefinitionId, final Integer limit,
                                                        final Long from, final Long to) {
        final List<HistoryEntry> databaseHistory = checkDefinitionSProc.getCheckDefinitionHistory(checkDefinitionId,
                resolveLimit(limit, from, to), secondsToDate(from), secondsToDate(to));

        final List<ActivityDiff> history = new LinkedList<>();
        for (final HistoryEntry entry : databaseHistory) {
            history.add(createActivityDiff(entry, resolveCheckDefinitionEventType(entry.getAction())));
        }

        return history;
    }

    @Override
    public List<ActivityDiff> getAlertDefinitionHistory(final int alertDefinitionId, final Integer limit,
                                                        final Long from, final Long to) {
        final List<HistoryEntry> databaseHistory = alertDefinitionSProc.getAlertDefinitionHistory(alertDefinitionId,
                resolveLimit(limit, from, to), secondsToDate(from), secondsToDate(to));

        final List<ActivityDiff> history = new LinkedList<>();
        for (final HistoryEntry entry : databaseHistory) {
            history.add(createActivityDiff(entry, resolveAlertDefinitionEventType(entry.getAction())));
        }

        return history;
    }

    private Integer resolveLimit(final Integer limit, final Long from, final Long to) {
        return limit != null ? limit : from == null && to == null ? DEFAULT_HISTORY_LIMIT : null;
    }

    private Date secondsToDate(final Long time) {
        return time == null ? null : new Date(time * 1000);
    }

    private long dateToSeconds(final Date date) {
        return date.getTime() / 1000;
    }

    private ActivityDiff createActivityDiff(final HistoryEntry entry, final ZMonEventType eventType) {
        return fillActivityDiff(new ActivityDiff(), entry, eventType);
    }

    private ActivityDiff fillActivityDiff(final ActivityDiff activity, final HistoryEntry entry,
                                          final ZMonEventType eventType) {
        activity.setTime(dateToSeconds(entry.getTimestamp()));
        activity.setTypeId(eventType.getId());
        activity.setTypeName(eventType.getName());
        activity.setAttributes(entry.getRowData());
        activity.setRecordId(entry.getRecordId());
        activity.setAction(entry.getAction());
        activity.setChangedAttributes(entry.getChangedFields());
        activity.setLastModifiedBy(HistoryUtils.resolveModifiedBy(entry.getRowData(), entry.getChangedFields()));

        return activity;
    }

    private ZMonEventType resolveCheckDefinitionEventType(final HistoryAction action) {

        switch (action) {

            case INSERT:
                return ZMonEventType.CHECK_DEFINITION_CREATED;

            case UPDATE:
                return ZMonEventType.CHECK_DEFINITION_UPDATED;

            default:
                throw new IllegalArgumentException("Action not supported: " + action);
        }
    }

    private ZMonEventType resolveAlertDefinitionEventType(final HistoryAction action) {

        switch (action) {

            case INSERT:
                return ZMonEventType.ALERT_DEFINITION_CREATED;

            case UPDATE:
                return ZMonEventType.ALERT_DEFINITION_UPDATED;

            default:
                throw new IllegalArgumentException("Action not supported: " + action);
        }
    }

    private ZMonEventType resolveEventType(final HistoryAction action, final HistoryType historyType) {
        switch (historyType) {

            case CHECK_DEFINITION:
                return resolveCheckDefinitionEventType(action);

            case ALERT_DEFINITION:
                return resolveAlertDefinitionEventType(action);

            default:
                throw new IllegalArgumentException("History type not supported: " + action);
        }

    }
}
