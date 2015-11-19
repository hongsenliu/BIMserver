package org.bimserver.database.queries;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.bimserver.BimServer;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.database.DatabaseSession;
import org.bimserver.database.actions.ObjectProvidingStackFrame;
import org.bimserver.emf.MetaDataManager;
import org.bimserver.plugins.serializers.ObjectProvider;
import org.bimserver.shared.HashMapVirtualObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class QueryObjectProvider implements ObjectProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(QueryObjectProvider.class);
	private DatabaseSession databaseSession;
	private BimServer bimServer;
	
	private String json;
	
	private final Set<Long> oidsRead = new HashSet<>();
	private Deque<StackFrame> stack;
	private long start = -1;
	private long reads = 0;
	private long stackFramesProcessed = 0;
	private ObjectNode fullQuery;
	
	public QueryObjectProvider(DatabaseSession databaseSession, BimServer bimServer, String json, Set<Long> roids) throws JsonParseException, JsonMappingException, IOException {
		this.databaseSession = databaseSession;
		this.bimServer = bimServer;
		this.json = json;
		
		ObjectMapper objectMapper = new ObjectMapper();
		fullQuery = objectMapper.readValue(json, ObjectNode.class);
		
		stack = new ArrayDeque<StackFrame>();
		stack.push(new StartFrame(this, roids));
	}
	
	public ObjectNode getFullQuery() {
		return fullQuery;
	}

	@Override
	public HashMapVirtualObject next() throws BimserverDatabaseException {
		if (start == -1) {
			start = System.nanoTime();
		}
		try {
			while (!stack.isEmpty()) {
				if (stack.size() > 1000) {
					dumpEndQuery();
					throw new BimserverDatabaseException("Query stack size > 1000, probably a bug, please report");
				}
				StackFrame stackFrame = stack.peek();
				if (stackFrame.isDone()) {
					stack.pop();
					continue;
				}
				stackFramesProcessed++;
				if (stackFramesProcessed > 1000000) {
					dumpEndQuery();
					throw new BimserverDatabaseException("Too many stack frames processed, probably a bug, please report");
				}
				boolean done = stackFrame.process();
				stackFrame.setDone(done);
				if (stackFrame instanceof ObjectProvidingStackFrame) {
					HashMapVirtualObject currentObject = ((ObjectProvidingStackFrame) stackFrame).getCurrentObject();
					if (currentObject != null) {
						if (!oidsRead.contains(currentObject.getOid())) {
							oidsRead.add(currentObject.getOid());
							return currentObject;
						}
					}
				}
			}
		} catch (Exception e) {
			throw new BimserverDatabaseException(e);
		}

		dumpEndQuery();
		
		return null;
	}
	
	private void dumpEndQuery() {
		StackFrame poll = stack.poll();
		int i=0;
		if (poll != null) {
			LOGGER.info("Query dump");
			while (poll != null && i < 20) {
				i++;
				LOGGER.info("\t" + poll.toString());
				poll = stack.poll();
			}
		}
		long end = System.nanoTime();
		LOGGER.info("Query, " + reads + " reads, " + stackFramesProcessed + " stack frames processed, " + oidsRead.size() + " objects read, " + ((end - start) / 1000000) + "ms");
	}

	public void incReads() {
		reads++;
	}

	public DatabaseSession getDatabaseSession() {
		return databaseSession;
	}

	public MetaDataManager getMetaDataManager() {
		return bimServer.getMetaDataManager();
	}

	public String getJsonString() {
		return json;
	}

	public boolean hasRead(long oid) {
		return oidsRead.contains(oid);
	}

	public void push(StackFrame stackFrame) {
		stack.push(stackFrame);
	}
}