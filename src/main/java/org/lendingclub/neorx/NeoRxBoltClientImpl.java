package org.lendingclub.neorx;

import static org.neo4j.driver.v1.Values.parameters;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.types.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ValueNode;

import io.reactivex.Observable;

class NeoRxBoltClientImpl extends NeoRxClient {

	Driver driver;

	static Logger logger = LoggerFactory.getLogger(NeoRxBoltClientImpl.class);
	static ObjectMapper mapper = new ObjectMapper();

	protected NeoRxBoltClientImpl(Driver driver) {
		this.driver = driver;
	}

	static JsonNode toJson(Node n) {
		JsonNode node = mapper.convertValue(n.asMap(), JsonNode.class);

		return node;
	}

	static JsonNode toObjectNode(Record record) {

		JsonNode rval = NullNode.getInstance();
		if (record == null) {

			return NullNode.getInstance();
		}
		Map<String, Object> m = record.asMap();
		if (m.size() == 1) {
			Object val = m.values().iterator().next();
			if (val instanceof Node) {
				rval = toJson((Node) val);
			} else {
				rval = mapper.convertValue(val, JsonNode.class);
			}
		} else {

			ObjectNode n = mapper.createObjectNode();
			m.entrySet().forEach(it -> {

				Object val = it.getValue();
				if (val instanceof Node) {
					Node node = (Node) val;
					n.set(it.getKey(), toJson(node));
				} else {
					n.set(it.getKey(), mapper.convertValue(it.getValue(), JsonNode.class));
				}

			});
			rval = n;
		}

		if (rval == null) {
			rval = NullNode.getInstance();
		}
		return rval;

	}

	static List<JsonNode> toList(StatementResult sr) {
		try {
			List<JsonNode> list = new LinkedList<>();
			while (sr.hasNext()) {

				Record record = sr.next();

				JsonNode n = toObjectNode(record);

				list.add(n);

			}
			return list;
		} catch (ClientException e) {
			throw new NeoRxException(e.getMessage(), e);
		}
	}

	static Object convertValueType(Object input) {
		Object rval = input;
		if (input == null) {
			rval = null; // unnecessary, but clear
		} else if (input.getClass().isPrimitive()) {
			rval = input;
		} else if (input instanceof String) {
			rval = input;
		} else if (input instanceof ObjectNode) {
			rval = mapper.convertValue(input, Map.class);
		} else if (input instanceof ArrayNode) {
			rval = mapper.convertValue(input, List.class);
		} else if (input instanceof MissingNode) {
			rval = null;
		} else if (input instanceof NullNode) {
			rval = null;
		} else if (input instanceof ValueNode) {
			ValueNode vn = ValueNode.class.cast(input);

			if (vn.isTextual()) {
				rval = TextNode.class.cast(input).asText();
			} else if (vn.isLong()) {
				rval = vn.asLong();
			} else if (vn.isInt()) {
				rval = vn.asInt();
			}

			else if (vn.isBoolean()) {
				rval = vn.booleanValue();
			} else if (vn.isDouble()) {
				rval = vn.asDouble();
			} else if (vn.isInt()) {
				rval = vn.asInt();
			}
		}

		if (logger.isDebugEnabled()) {
			logger.debug("convert <<{}>> ({}) to <<{}>> ({})", input, input == null ? "null" : input.getClass(), rval,
					rval == null ? "null" : rval.getClass());
		}
		return rval;
	}

	protected static Object[] convertExtraTypes(Object... keysAndValues) {
		if (keysAndValues.length % 2 != 0) {
			// we throw the same client exception as the underlying Driver
			// would, but wrap it for consistency with
			// NeoRx exception hierarchy.

			throw new NeoRxException(
					new ClientException("Parameters function requires an even number " + "of arguments, "
							+ "alternating key and value. Arguments were: " + Arrays.toString(keysAndValues) + "."));
		}
		for (int i = 0; i < keysAndValues.length; i += 2) {
			keysAndValues[i + 1] = convertValueType(keysAndValues[i + 1]);

		}

		return keysAndValues;

	}

	public io.reactivex.Observable<JsonNode> execCypher(String cypher, ObjectNode args) {
		List<Object> list = new LinkedList<>();
		args.fields().forEachRemaining(it -> {
			list.add(it.getKey());
			list.add(convertValueType(it.getValue()));
		});

		return execCypher(cypher, list.toArray());
	}

	public Observable<JsonNode> execCypher(String cypher, Object... args) {
		// Note that there is nothing reactive, async or efficient about this.
		// But it is VERY usable.
		Session session = driver.session();
		try {
			if (logger.isDebugEnabled()) {
				logger.debug("cypher={}", cypher);
			}

			StatementResult result = session.run(cypher, parameters(convertExtraTypes(args)));
			// This is inefficient to take the result, turn it into a list and
			// then back into an Observable.
			// We will enhance this to stream the statement result as an
			// Observable directly
			return Observable.fromIterable(toList(result));
		} catch (ClientException e) {
			throw new NeoRxException(e.getMessage(), e);
		} finally {
			session.close();
		}
	}

	public Driver getDriver() {
		return driver;
	}

	@Override
	public boolean checkConnection() {
		try {
			execCypher("match (a:Check__Session) return count(a)");
			return true;
		} catch (Exception e) {
			return false;
		}
	}

}
