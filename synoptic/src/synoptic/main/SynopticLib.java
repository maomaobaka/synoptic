package synoptic.main;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import synoptic.main.options.AbstractOptions;
import synoptic.main.options.SynopticOptions;
import synoptic.main.parser.ParseException;
import synoptic.model.ChainsTraceGraph;
import synoptic.model.EventNode;
import synoptic.model.PartitionGraph;
import synoptic.model.Relation;
import synoptic.model.event.Event;
import synoptic.model.event.EventType;
import synoptic.model.event.GenericEventType;
import synoptic.model.export.types.SynGraph;
import synoptic.util.InternalSynopticException;
import synoptic.util.resource.ITotalResource;

/**
 * The library version of Synoptic. Most users should only need to run the
 * {@link #inferModel} method TODO:
 */
public class SynopticLib<T extends Comparable<T>> extends AbstractMain {

    private List<List<T>> rawTraces = null;

    private static int nextTraceID = 0;

    // Maps a unique partition label to a set of parsed events corresponding to
    // that partition
    Map<T, List<EventNode>> partitions = new LinkedHashMap<>();

    // EventNode -> Relation associated with this event node.
    Map<EventNode, Set<Relation>> allEventRelations = new HashMap<>();

    /**
     * Return the singleton instance of SynopticLib, first asserting that the
     * instance isn't null
     */
    public static SynopticLib getInstance() {
        assert (instance != null);
        assert (instance instanceof SynopticLib);
        return (SynopticLib) instance;
    }

    /**
     * Perform the Synoptic inference algorithm. See user documentation for an
     * explanation of the options
     */
    public static <T extends Comparable<T>> SynGraph<T> inferModel(
            SynopticOptions synOptions, List<List<T>> traces) throws Exception {

        // Construct main object
        AbstractOptions options = synOptions.toAbstractOptions();
        AbstractOptions.keepOrder = true; // TODO: do something proper
        SynopticLib<T> mainInstance = new SynopticLib<>(options);

        mainInstance.rawTraces = traces;

        try {
            Locale.setDefault(Locale.US);

            PartitionGraph pGraph = mainInstance.createInitialPartitionGraph();
            if (pGraph != null) {
                mainInstance.runSynoptic(pGraph);
            }
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw InternalSynopticException.wrap(e);
        }

        return null;
    }

    /**
     * Constructor that simply stores parameters in fields and initializes the
     * pseudo RNG
     * 
     * @param opts
     *            Processed options from the command line
     */
    public SynopticLib(AbstractOptions opts) {
        setUpLogging(opts);

        if (AbstractMain.instance != null) {
            throw new RuntimeException(
                    "Cannot create multiple instances of singleton synoptic.main.AbstractMain");
        }
        this.options = opts;
        this.random = new Random(opts.randomSeed);
        logger.info("Using random seed: " + opts.randomSeed);
        partitions = new HashMap<>();
        AbstractMain.instance = this;
    }

    /**
     * 
     */
    @Override
    protected ChainsTraceGraph makeTraceGraph() throws Exception {
        List<EventNode> allEvents = wrapTracesAsEvents();

        if (allEvents.size() == 0) {
            logger.severe("Input traces were empty or invalid. Stopping.");
            return null;
        }

        genChainsTraceGraph(allEvents);

        return null;
    }

    /**
     * 
     */
    private List<EventNode> wrapTracesAsEvents() {
        long startTime = loggerInfoStart(
                "Converting traces to Synoptic events...");

        List<EventNode> allEvents = new ArrayList<EventNode>();
        int resourceVal = 0; // TODO: is this necessary?

        // Set up single relation that will be shared by all events
        Set<Relation> relations = new HashSet<>();
        Relation timeRelation = new Relation("time-relation",
                Event.defTimeRelationStr, false);
        relations.add(timeRelation);

        for (List<T> rawTrace : rawTraces) {
            // Each event
            for (T rawEvent : rawTrace) {

                EventType eType = new GenericEventType<T>(rawEvent);
                Event event = new Event(eType, "", "", 0);
                event.setTime(new ITotalResource(resourceVal++));

                // Add event to the partition labeled with this raw event object
                EventNode eventNode = addEventNodeToPartition(event, rawEvent);

                // We want to add event relations ONLY IF eventNode actually
                // represents an event, not a dummy state
                if (!eventNode.getEType().isSpecialEventType()) {
                    allEventRelations.put(eventNode, relations);
                }

                allEvents.add(eventNode);
            }
        }

        loggerInfoEnd("Converting traces took ", startTime);
        return null;
    }

    /**
     * 
     */
    private EventNode addEventNodeToPartition(Event event, T pName) {
        EventNode eventNode = new EventNode(event);

        List<EventNode> events = null;
        for (T otherPName : partitions.keySet()) {
            if (otherPName.equals(pName)) {
                events = partitions.get(otherPName);
            }
        }

        if (events == null) {
            events = new ArrayList<>();
            partitions.put(pName, events);
            logger.fine("Created partition '" + pName + "'");

            nextTraceID++;
        }
        eventNode.setTraceID(nextTraceID);

        // We want to add eventNode to partitions ONLY IF event actually
        // represents an event, not a dummy for state.
        if (!event.getEType().isSpecialEventType()) {
            events.add(eventNode);
        }
        return eventNode;
    }

    /**
     * 
     */
    private ChainsTraceGraph genChainsTraceGraph(List<EventNode> parsedEvents)
            throws ParseException {
        long startTime = loggerInfoStart(
                "Generating inter-event temporal relation...");
        ChainsTraceGraph inputGraph = generateDirectTORelation(parsedEvents);
        loggerInfoEnd("Generating temporal relation took ", startTime);
        return inputGraph;
    }

    /**
     * 
     */
    private ChainsTraceGraph generateDirectTORelation(List<EventNode> allEvents)
            throws ParseException {
        //
        ChainsTraceGraph graph = new ChainsTraceGraph(allEvents);
        for (T pName : partitions.keySet()) {
            graph.addTrace(partitions.get(pName), allEventRelations);
        }
        return graph;
    }
}