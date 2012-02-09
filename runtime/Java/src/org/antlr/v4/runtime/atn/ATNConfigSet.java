/*
 * [The "BSD license"]
 *  Copyright (c) 2012 Sam Harwell
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *  1. Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *      derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.antlr.v4.runtime.atn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 *
 * @author Sam Harwell
 */
public class ATNConfigSet implements Set<ATNConfig> {

	private final boolean localContext;
	private final Map<Long, ATNConfig> mergedConfigs;
	private final List<ATNConfig> unmerged;
	private final List<ATNConfig> configs;

	public int outerContextDepth;

	private int uniqueAlt;
	private IntervalSet conflictingAlts;
	private boolean hasSemanticContext;
	private boolean dipsIntoOuterContext;

	public ATNConfigSet(boolean localContext) {
		this.localContext = localContext;
		this.mergedConfigs = new HashMap<Long, ATNConfig>();
		this.unmerged = new ArrayList<ATNConfig>();
		this.configs = new ArrayList<ATNConfig>();

		this.uniqueAlt = ATN.INVALID_ALT_NUMBER;
	}

	private ATNConfigSet(ATNConfigSet set, boolean readonly) {
		this.localContext = set.localContext;

		if (readonly) {
			this.mergedConfigs = null;
			this.unmerged = null;
		} else {
			this.mergedConfigs = new HashMap<Long, ATNConfig>(set.mergedConfigs);
			this.unmerged = new ArrayList<ATNConfig>(set.unmerged);
		}

		this.configs = new ArrayList<ATNConfig>(set.configs);

		this.outerContextDepth = set.outerContextDepth;
		this.dipsIntoOuterContext = set.dipsIntoOuterContext;
		this.hasSemanticContext = set.hasSemanticContext;
		this.uniqueAlt = set.uniqueAlt;
		this.conflictingAlts = set.conflictingAlts;
	}

	public Set<ATNState> getStates() {
		Set<ATNState> states = new HashSet<ATNState>();
		for (ATNConfig c : this.configs) {
			states.add(c.state);
		}

		return states;
	}

	public ATNConfigSet clone(boolean readonly) {
		return new ATNConfigSet(this, readonly);
	}

	@Override
	public int size() {
		return configs.size();
	}

	@Override
	public boolean isEmpty() {
		return configs.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		if (!(o instanceof ATNConfig)) {
			return false;
		}

		ATNConfig config = (ATNConfig)o;
		ATNConfig mergedConfig = mergedConfigs.get(getKey(config));
		if (mergedConfig != null && canMerge(config, mergedConfig)) {
			return mergedConfig.contains(config);
		}

		for (ATNConfig c : unmerged) {
			if (c.contains(config)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public Iterator<ATNConfig> iterator() {
		return new ATNConfigSetIterator();
	}

	@Override
	public Object[] toArray() {
		return configs.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return configs.toArray(a);
	}

	@Override
	public boolean add(ATNConfig e) {
		ensureWritable();

		boolean added;
		boolean addKey;
		long key = getKey(e);
		ATNConfig mergedConfig = mergedConfigs.get(key);
		addKey = (mergedConfig == null);
		if (mergedConfig != null && canMerge(e, key, mergedConfig)) {
			mergedConfig.reachesIntoOuterContext = Math.max(mergedConfig.reachesIntoOuterContext, e.reachesIntoOuterContext);

			PredictionContext joined = PredictionContext.join(mergedConfig.context, e.context, localContext);
			if (mergedConfig.context == joined) {
				return false;
			}

			mergedConfig.context = joined;
			updatePropertiesForMergedConfig(e);
			return true;
		}

		for (int i = 0; i < unmerged.size(); i++) {
			ATNConfig unmergedConfig = unmerged.get(i);
			if (canMerge(e, key, unmergedConfig)) {
				unmergedConfig.reachesIntoOuterContext = Math.max(unmergedConfig.reachesIntoOuterContext, e.reachesIntoOuterContext);

				PredictionContext joined = PredictionContext.join(unmergedConfig.context, e.context, localContext);
				if (unmergedConfig.context == joined) {
					return false;
				}

				unmergedConfig.context = joined;

				if (addKey) {
					mergedConfigs.put(key, unmergedConfig);
					unmerged.remove(i);
				}

				updatePropertiesForMergedConfig(e);
				return true;
			}
		}

		added = true;

		if (added) {
			configs.add(e);
			if (addKey) {
				mergedConfigs.put(key, e);
			} else {
				unmerged.add(e);
			}

			updatePropertiesForAddedConfig(e);
		}

		return added;
	}

	private void updatePropertiesForMergedConfig(ATNConfig config) {
		// merged configs can't change the alt or semantic context
		dipsIntoOuterContext |= config.reachesIntoOuterContext > 0;
	}

	private void updatePropertiesForAddedConfig(ATNConfig config) {
		if (configs.size() == 1) {
			uniqueAlt = config.alt;
		} else if (uniqueAlt != config.alt) {
			uniqueAlt = ATN.INVALID_ALT_NUMBER;
		}

		hasSemanticContext |= !SemanticContext.NONE.equals(config.semanticContext);
		dipsIntoOuterContext |= config.reachesIntoOuterContext > 0;
	}

	private static boolean canMerge(ATNConfig left, ATNConfig right) {
		if (getKey(left) != getKey(right)) {
			return false;
		}

		return left.semanticContext.equals(right.semanticContext);
	}

	private static boolean canMerge(ATNConfig left, long leftKey, ATNConfig right) {
		if (left.state.stateNumber != right.state.stateNumber) {
			return false;
		}

		if (leftKey != getKey(right)) {
			return false;
		}

		return left.semanticContext.equals(right.semanticContext);
	}

	private static long getKey(ATNConfig e) {
		long key = ((long)e.state.stateNumber << 32) + (e.alt << 3);
		//key |= e.reachesIntoOuterContext != 0 ? 1 : 0;
		//key |= e.resolveWithPredicate ? 1 << 1 : 0;
		//key |= e.traversedPredicate ? 1 << 2 : 0;
		return key;
	}

	@Override
	public boolean remove(Object o) {
		ensureWritable();

		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		for (Object o : c) {
			if (!(o instanceof ATNConfig)) {
				return false;
			}

			if (!contains((ATNConfig)o)) {
				return false;
			}
		}

		return true;
	}

	@Override
	public boolean addAll(Collection<? extends ATNConfig> c) {
		ensureWritable();

		boolean changed = false;
		for (ATNConfig group : c) {
			changed |= add(group);
		}

		return changed;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		ensureWritable();
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		ensureWritable();
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public void clear() {
		ensureWritable();

		mergedConfigs.clear();
		unmerged.clear();
		configs.clear();

		outerContextDepth = 0;

		dipsIntoOuterContext = false;
		hasSemanticContext = false;
		uniqueAlt = ATN.INVALID_ALT_NUMBER;
		conflictingAlts = null;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof ATNConfigSet)) {
			return false;
		}

		ATNConfigSet other = (ATNConfigSet)obj;
		return this.localContext == other.localContext
			&& configs.equals(other.configs);
	}

	@Override
	public int hashCode() {
		int hashCode = 1;
		hashCode = 5 * hashCode + (localContext ? 1 : 0);
		hashCode = 5 * hashCode + configs.hashCode();
		return hashCode;
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(configs.toString());
		if ( hasSemanticContext ) buf.append(",hasSemanticContext="+hasSemanticContext);
		if ( uniqueAlt!=ATN.INVALID_ALT_NUMBER ) buf.append(",uniqueAlt="+uniqueAlt);
		if ( conflictingAlts!=null ) buf.append(",conflictingAlts="+conflictingAlts);
		if ( dipsIntoOuterContext ) buf.append(",dipsIntoOuterContext");
		return buf.toString();
	}

	public int getUniqueAlt() {
		return uniqueAlt;
	}

	public boolean hasSemanticContext() {
		return hasSemanticContext;
	}

	public IntervalSet getConflictingAlts() {
		return conflictingAlts;
	}

	public void setConflictingAlts(IntervalSet conflictingAlts) {
		//ensureWritable(); <-- these do end up set after the DFAState is created, but set to a distinct value
		this.conflictingAlts = conflictingAlts;
	}

	public boolean getDipsIntoOuterContext() {
		return dipsIntoOuterContext;
	}

	public ATNConfig get(int index) {
		return configs.get(index);
	}

	public void remove(int index) {
		ensureWritable();
		ATNConfig config = configs.get(index);
		configs.remove(config);
		long key = getKey(config);
		if (mergedConfigs.get(key) == config) {
			mergedConfigs.remove(key);
		} else {
			for (int i = 0; i < unmerged.size(); i++) {
				if (unmerged.get(i) == config) {
					unmerged.remove(i);
					return;
				}
			}
		}
	}

	protected final void ensureWritable() {
		boolean readonly = mergedConfigs == null;
		if (readonly) {
			throw new IllegalStateException("This ATNConfigSet is read only.");
		}
	}

	private final class ATNConfigSetIterator implements Iterator<ATNConfig> {

		int index = -1;
		boolean removed = false;

		@Override
		public boolean hasNext() {
			return index + 1 < configs.size();
		}

		@Override
		public ATNConfig next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}

			index++;
			removed = false;
			return configs.get(index);
		}

		@Override
		public void remove() {
			if (removed || index < 0 || index >= configs.size()) {
				throw new IllegalStateException();
			}

			ATNConfigSet.this.remove(index);
			removed = true;
		}

	}
}
