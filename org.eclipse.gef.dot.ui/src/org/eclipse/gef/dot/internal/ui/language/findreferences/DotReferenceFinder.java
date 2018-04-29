/*******************************************************************************
 * Copyright (c) 2018 itemis AG and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Tamas Miklossy (itemis AG) - initial API and implementation (bug #531049)
 *
 *******************************************************************************/
package org.eclipse.gef.dot.internal.ui.language.findreferences;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.dot.internal.language.DotAstHelper;
import org.eclipse.gef.dot.internal.language.dot.NodeId;
import org.eclipse.gef.dot.internal.ui.language.internal.DotActivator;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.findReferences.IReferenceFinder;
import org.eclipse.xtext.findReferences.ReferenceFinder;
import org.eclipse.xtext.findReferences.TargetURIs;

import com.google.inject.Injector;

public class DotReferenceFinder extends ReferenceFinder {

	private DotReferenceFinder() {
	}

	private static DotReferenceFinder INSTANCE;

	/* package-private */ static DotReferenceFinder getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new DotReferenceFinder();
			Injector injector = DotActivator.getInstance().getInjector(
					DotActivator.ORG_ECLIPSE_GEF_DOT_INTERNAL_LANGUAGE_DOT);
			injector.injectMembers(INSTANCE);
		}

		return INSTANCE;
	}

	@Override
	protected IReferenceFinder getLanguageSpecificReferenceFinder(
			URI candidate) {
		return getInstance();
	}

	/**
	 * Using the @Override notation leads to a compile error on some platforms,
	 * since this internal Xtext API has been changed over time. Thus, it is not
	 * guaranted that this custom method implementation will be invoked on all
	 * supported platforms. TODO: find a general solution to provide this
	 * functionality on all supported platforms.
	 **/
	public void findReferences(TargetURIs targetURIs, Resource resource,
			Acceptor acceptor, IProgressMonitor monitor) {
		super.findReferences(targetURIs, resource, acceptor, monitor);

		// add DOT speficic references
		for (URI targetURI : targetURIs) {
			EObject target = resource.getEObject(targetURI.fragment());
			// currently, only a selection of a nodeId is supported
			if (target instanceof NodeId) {
				acceptor.accept(target, targetURI, null, -1, target, targetURI);
				NodeId selectedNodeId = (NodeId) target;
				for (NodeId source : DotAstHelper
						.getAllNodeIds(selectedNodeId)) {
					URI sourceURI = EcoreUtil2
							.getPlatformResourceOrNormalizedURI(source);
					acceptor.accept(source, sourceURI, null, -1, target,
							targetURI);
				}
			}
		}
	}
}