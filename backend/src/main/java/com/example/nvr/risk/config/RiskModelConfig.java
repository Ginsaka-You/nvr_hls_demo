package com.example.nvr.risk.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of the YAML 风控模型配置。
 */
public class RiskModelConfig {

    private String version;
    private Parameters parameters;
    private List<PriorityDefinition> priorities = new ArrayList<>();
    private List<ActionDefinition> actions = new ArrayList<>();
    @JsonProperty("fRules")
    private List<FRuleDefinition> fRules = new ArrayList<>();
    @JsonProperty("gRules")
    private List<GRuleDefinition> gRules = new ArrayList<>();
    private StateMachineDefinition stateMachine = new StateMachineDefinition();

    @JsonIgnore
    private Map<String, PriorityDefinition> priorityIndex;

    @JsonIgnore
    private Map<String, ActionDefinition> actionIndex;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Parameters getParameters() {
        return parameters;
    }

    public void setParameters(Parameters parameters) {
        this.parameters = parameters;
    }

    public List<PriorityDefinition> getPriorities() {
        return priorities;
    }

    public void setPriorities(List<PriorityDefinition> priorities) {
        this.priorities = priorities != null ? new ArrayList<>(priorities) : new ArrayList<>();
        this.priorityIndex = null;
    }

    public List<ActionDefinition> getActions() {
        return actions;
    }

    public void setActions(List<ActionDefinition> actions) {
        this.actions = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
        this.actionIndex = null;
    }

    public List<FRuleDefinition> getFRules() {
        return fRules;
    }

    public void setFRules(List<FRuleDefinition> fRules) {
        this.fRules = fRules != null ? new ArrayList<>(fRules) : new ArrayList<>();
    }

    public List<GRuleDefinition> getGRules() {
        return gRules;
    }

    public void setGRules(List<GRuleDefinition> gRules) {
        this.gRules = gRules != null ? new ArrayList<>(gRules) : new ArrayList<>();
    }

    public StateMachineDefinition getStateMachine() {
        return stateMachine;
    }

    public void setStateMachine(StateMachineDefinition stateMachine) {
        this.stateMachine = stateMachine != null ? stateMachine : new StateMachineDefinition();
    }

    public PriorityDefinition findPriority(String id) {
        if (id == null) {
            return null;
        }
        ensurePriorityIndex();
        return priorityIndex.get(id.toUpperCase());
    }

    public ActionDefinition findAction(String id) {
        if (id == null) {
            return null;
        }
        ensureActionIndex();
        return actionIndex.get(id.toUpperCase());
    }

    private void ensurePriorityIndex() {
        if (priorityIndex == null) {
            Map<String, PriorityDefinition> map = new HashMap<>();
            for (PriorityDefinition def : priorities) {
                if (def != null && def.getId() != null) {
                    map.put(def.getId().toUpperCase(), def);
                }
            }
            priorityIndex = map;
        }
    }

    private void ensureActionIndex() {
        if (actionIndex == null) {
            Map<String, ActionDefinition> map = new HashMap<>();
            for (ActionDefinition def : actions) {
                if (def != null && def.getId() != null) {
                    map.put(def.getId().toUpperCase(), def);
                }
            }
            actionIndex = map;
        }
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("version", version);
        meta.put("parameters", parameters != null ? parameters.toMetadata() : Collections.emptyMap());
        meta.put("priorities", priorities);
        meta.put("actions", actions);
        meta.put("fRules", fRules);
        meta.put("gRules", gRules);
        meta.put("stateMachine", stateMachine);
        return meta;
    }

    public static class Parameters {
        private String timezone = "Asia/Shanghai";
        private int nightStartHour = 18;
        private int nightEndHour = 6;
        private Duration mergeWindow = Duration.ofMinutes(5);
        private Duration analysisWindow = Duration.ofMinutes(30);
        private Duration historyWindow = Duration.ofHours(6);
        private Duration challengeWindow = Duration.ofMinutes(5);
        private Duration imsiReentryWindow = Duration.ofMinutes(30);
        private Duration imsiDedupWindow = Duration.ofMinutes(5);
        private Duration f1ToF2LinkWindow = Duration.ofMinutes(4);
        private Duration radarPersistThreshold = Duration.ofSeconds(10);
        private double radarNearCoreMeters = 10.0;
        private List<String> imsiWhitelist = new ArrayList<>();

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public int getNightStartHour() {
            return nightStartHour;
        }

        public void setNightStartHour(int nightStartHour) {
            this.nightStartHour = nightStartHour;
        }

        public int getNightEndHour() {
            return nightEndHour;
        }

        public void setNightEndHour(int nightEndHour) {
            this.nightEndHour = nightEndHour;
        }

        public Duration getMergeWindow() {
            return mergeWindow;
        }

        public void setMergeWindow(Duration mergeWindow) {
            this.mergeWindow = mergeWindow;
        }

        public Duration getAnalysisWindow() {
            return analysisWindow;
        }

        public void setAnalysisWindow(Duration analysisWindow) {
            this.analysisWindow = analysisWindow;
        }

        public Duration getHistoryWindow() {
            return historyWindow;
        }

        public void setHistoryWindow(Duration historyWindow) {
            this.historyWindow = historyWindow;
        }

        public Duration getChallengeWindow() {
            return challengeWindow;
        }

        public void setChallengeWindow(Duration challengeWindow) {
            this.challengeWindow = challengeWindow;
        }

        public Duration getImsiReentryWindow() {
            return imsiReentryWindow;
        }

        public void setImsiReentryWindow(Duration imsiReentryWindow) {
            this.imsiReentryWindow = imsiReentryWindow;
        }

        public Duration getImsiDedupWindow() {
            return imsiDedupWindow;
        }

        public void setImsiDedupWindow(Duration imsiDedupWindow) {
            this.imsiDedupWindow = imsiDedupWindow;
        }

        public Duration getF1ToF2LinkWindow() {
            return f1ToF2LinkWindow;
        }

        public void setF1ToF2LinkWindow(Duration f1ToF2LinkWindow) {
            this.f1ToF2LinkWindow = f1ToF2LinkWindow;
        }

        public Duration getRadarPersistThreshold() {
            return radarPersistThreshold;
        }

        public void setRadarPersistThreshold(Duration radarPersistThreshold) {
            this.radarPersistThreshold = radarPersistThreshold;
        }

        public double getRadarNearCoreMeters() {
            return radarNearCoreMeters;
        }

        public void setRadarNearCoreMeters(double radarNearCoreMeters) {
            this.radarNearCoreMeters = radarNearCoreMeters;
        }

        public List<String> getImsiWhitelist() {
            return imsiWhitelist;
        }

        public void setImsiWhitelist(List<String> imsiWhitelist) {
            this.imsiWhitelist = imsiWhitelist != null ? new ArrayList<>(imsiWhitelist) : new ArrayList<>();
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> meta = new HashMap<>();
            meta.put("timezone", timezone);
            meta.put("nightStartHour", nightStartHour);
            meta.put("nightEndHour", nightEndHour);
            meta.put("mergeWindow", mergeWindow);
            meta.put("analysisWindow", analysisWindow);
            meta.put("historyWindow", historyWindow);
            meta.put("challengeWindow", challengeWindow);
            meta.put("imsiReentryWindow", imsiReentryWindow);
            meta.put("imsiDedupWindow", imsiDedupWindow);
            meta.put("f1ToF2LinkWindow", f1ToF2LinkWindow);
            meta.put("radarPersistThreshold", radarPersistThreshold);
            meta.put("radarNearCoreMeters", radarNearCoreMeters);
            meta.put("imsiWhitelist", imsiWhitelist);
            return meta;
        }
    }

    public static class PriorityDefinition {
        private String id;
        private String name;
        private String description;
        private String guidance;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getGuidance() {
            return guidance;
        }

        public void setGuidance(String guidance) {
            this.guidance = guidance;
        }
    }

    public static class ActionDefinition {
        private String id;
        private String name;
        private String description;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class FRuleDefinition {
        private String id;
        private String name;
        private String trigger;
        private String dataSource;
        private String window;
        private String frequencyLimit;
        private String cooldown;
        private String actionImpact;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTrigger() {
            return trigger;
        }

        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }

        public String getDataSource() {
            return dataSource;
        }

        public void setDataSource(String dataSource) {
            this.dataSource = dataSource;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }

        public String getFrequencyLimit() {
            return frequencyLimit;
        }

        public void setFrequencyLimit(String frequencyLimit) {
            this.frequencyLimit = frequencyLimit;
        }

        public String getCooldown() {
            return cooldown;
        }

        public void setCooldown(String cooldown) {
            this.cooldown = cooldown;
        }

        public String getActionImpact() {
            return actionImpact;
        }

        public void setActionImpact(String actionImpact) {
            this.actionImpact = actionImpact;
        }
    }

    public static class GRuleDefinition {
        private String id;
        private String name;
        private String trigger;
        private String action;
        private String notes;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTrigger() {
            return trigger;
        }

        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    public static class StateMachineDefinition {
        private List<StateDefinition> states = new ArrayList<>();
        private List<TransitionDefinition> transitions = new ArrayList<>();

        public List<StateDefinition> getStates() {
            return states;
        }

        public void setStates(List<StateDefinition> states) {
            this.states = states != null ? new ArrayList<>(states) : new ArrayList<>();
        }

        public List<TransitionDefinition> getTransitions() {
            return transitions;
        }

        public void setTransitions(List<TransitionDefinition> transitions) {
            this.transitions = transitions != null ? new ArrayList<>(transitions) : new ArrayList<>();
        }
    }

    public static class StateDefinition {
        private String id;
        private String name;
        private String description;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class TransitionDefinition {
        private String from;
        private String to;
        private String trigger;

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getTrigger() {
            return trigger;
        }

        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }
    }

    public void validate() {
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(version, "version must not be null");
        ensurePriorityIndex();
        ensureActionIndex();
    }
}
