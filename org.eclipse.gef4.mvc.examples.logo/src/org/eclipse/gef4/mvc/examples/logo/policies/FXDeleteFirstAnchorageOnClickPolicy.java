/*******************************************************************************
 * Copyright (c) 2014, 2015 itemis AG and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Matthias Wienand (itemis AG) - initial API & implementation
 *
 *******************************************************************************/
package org.eclipse.gef4.mvc.examples.logo.policies;

import org.eclipse.gef4.mvc.fx.policies.IFXOnClickPolicy;
import org.eclipse.gef4.mvc.parts.IContentPart;
import org.eclipse.gef4.mvc.parts.IVisualPart;
import org.eclipse.gef4.mvc.policies.AbstractInteractionPolicy;
import org.eclipse.gef4.mvc.policies.DeletionPolicy;

import com.google.common.reflect.TypeToken;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;

public class FXDeleteFirstAnchorageOnClickPolicy
		extends AbstractInteractionPolicy<Node>implements IFXOnClickPolicy {

	@SuppressWarnings("serial")
	@Override
	public void click(MouseEvent e) {
		IVisualPart<Node, ? extends Node> targetPart = getTargetPart();
		if (targetPart instanceof IContentPart) {
			DeletionPolicy<Node> policy = getHost().getRoot()
					.getAdapter(new TypeToken<DeletionPolicy<Node>>() {
					});
			if (policy != null) {
				init(policy);
				// unestablish anchor relations
				policy.delete((IContentPart<Node, ? extends Node>) targetPart);
				commit(policy);
			}
		}
	}

	/**
	 * Returns the target {@link IVisualPart} for this policy. Per default the
	 * first anchorage is returned.
	 *
	 * @return The target {@link IVisualPart} for this policy.
	 */
	protected IVisualPart<Node, ? extends Node> getTargetPart() {
		return getHost().getAnchoragesUnmodifiable().keySet().iterator().next();
	}

}
