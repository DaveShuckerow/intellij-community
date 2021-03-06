// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.engine;

import com.intellij.debugger.DebuggerBundle;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.actions.JavaReferringObjectsValue;
import com.intellij.debugger.actions.JumpToObjectAction;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl;
import com.intellij.debugger.engine.evaluation.expression.Modifier;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.impl.DebuggerContextImpl;
import com.intellij.debugger.impl.DebuggerUtilsEx;
import com.intellij.debugger.memory.agent.MemoryAgent;
import com.intellij.debugger.memory.agent.MemoryAgentReferringObjectsProvider;
import com.intellij.debugger.ui.impl.DebuggerTreeRenderer;
import com.intellij.debugger.ui.impl.watch.*;
import com.intellij.debugger.ui.tree.*;
import com.intellij.debugger.ui.tree.render.Renderer;
import com.intellij.debugger.ui.tree.render.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ThreeState;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.evaluation.XInstanceEvaluator;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XErrorValuePresentation;
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl;
import com.intellij.xdebugger.impl.frame.XValueWithInlinePresentation;
import com.intellij.xdebugger.impl.ui.XValueTextProvider;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
import java.util.List;
import java.util.Set;

/**
* @author egor
*/
public class JavaValue extends XNamedValue implements NodeDescriptorProvider, XValueTextProvider, XValueWithInlinePresentation {
  private static final Logger LOG = Logger.getInstance(JavaValue.class);

  private final JavaValue myParent;
  private final ValueDescriptorImpl myValueDescriptor;
  private final EvaluationContextImpl myEvaluationContext;
  private final NodeManagerImpl myNodeManager;
  private final boolean myContextSet;

  protected JavaValue(JavaValue parent,
                    @NotNull ValueDescriptorImpl valueDescriptor,
                    @NotNull EvaluationContextImpl evaluationContext,
                    NodeManagerImpl nodeManager,
                    boolean contextSet) {
    this(parent, valueDescriptor.calcValueName(), valueDescriptor, evaluationContext, nodeManager, contextSet);
  }

  protected JavaValue(JavaValue parent,
                      String name,
                      @NotNull ValueDescriptorImpl valueDescriptor,
                      @NotNull EvaluationContextImpl evaluationContext,
                      NodeManagerImpl nodeManager,
                      boolean contextSet) {
    super(name);
    myParent = parent;
    myValueDescriptor = valueDescriptor;
    myEvaluationContext = evaluationContext;
    myNodeManager = nodeManager;
    myContextSet = contextSet;
  }

  public static JavaValue create(JavaValue parent,
                          @NotNull ValueDescriptorImpl valueDescriptor,
                          @NotNull EvaluationContextImpl evaluationContext,
                          NodeManagerImpl nodeManager,
                          boolean contextSet) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    return new JavaValue(parent, valueDescriptor, evaluationContext, nodeManager, contextSet);
  }

  static JavaValue create(@NotNull ValueDescriptorImpl valueDescriptor,
                          @NotNull EvaluationContextImpl evaluationContext,
                          NodeManagerImpl nodeManager) {
    return create(null, valueDescriptor, evaluationContext, nodeManager, false);
  }

  public JavaValue getParent() {
    return myParent;
  }

  @Override
  @NotNull
  public ValueDescriptorImpl getDescriptor() {
    return myValueDescriptor;
  }

  @NotNull
  public EvaluationContextImpl getEvaluationContext() {
    return myEvaluationContext;
  }

  public NodeManagerImpl getNodeManager() {
    return myNodeManager;
  }

  private boolean isOnDemand() {
    return OnDemandRenderer.ON_DEMAND_CALCULATED.isIn(myValueDescriptor);
  }

  private boolean isCalculated() {
    return OnDemandRenderer.isCalculated(myValueDescriptor);
  }

  @Override
  public void computePresentation(@NotNull final XValueNode node, @NotNull XValuePlace place) {
    if (isOnDemand() && !isCalculated()) {
      node.setFullValueEvaluator(OnDemandRenderer.createFullValueEvaluator(DebuggerBundle.message("message.node.evaluate")));
      node.setPresentation(AllIcons.Debugger.Db_watch, new XRegularValuePresentation("", null, ""), false);
      return;
    }
    myEvaluationContext.getManagerThread().schedule(new SuspendContextCommandImpl(myEvaluationContext.getSuspendContext()) {
      @Override
      public Priority getPriority() {
        return Priority.NORMAL;
      }

      @Override
      protected void commandCancelled() {
        node.setPresentation(null, new XErrorValuePresentation(DebuggerBundle.message("error.context.has.changed")), false);
      }

      @Override
      public void contextAction(@NotNull SuspendContextImpl suspendContext) {
        if (node.isObsolete()) {
          return;
        }
        if (!myContextSet) {
          myValueDescriptor.setContext(myEvaluationContext);
        }
        myValueDescriptor.updateRepresentation(myEvaluationContext, new DescriptorLabelListener() {
          @Override
          public void labelChanged() {
            Icon nodeIcon = DebuggerTreeRenderer.getValueIcon(myValueDescriptor, myParent != null ? myParent.getDescriptor() : null);

            XValuePresentation presentation = createPresentation(myValueDescriptor);
            Renderer lastRenderer = myValueDescriptor.getLastRenderer();
            boolean fullEvaluatorSet = setFullValueEvaluator(lastRenderer);
            if (!fullEvaluatorSet && lastRenderer instanceof CompoundNodeRenderer) {
              fullEvaluatorSet = setFullValueEvaluator(((CompoundNodeRenderer)lastRenderer).getLabelRenderer());
            }
            if (!fullEvaluatorSet && getValueText().length() > XValueNode.MAX_VALUE_LENGTH) {
              node.setFullValueEvaluator(new JavaFullValueEvaluator(myEvaluationContext) {
                @Override
                public void evaluate(@NotNull final XFullValueEvaluationCallback callback) {
                  final ValueDescriptorImpl fullValueDescriptor = myValueDescriptor.getFullValueDescriptor();
                  fullValueDescriptor.updateRepresentation(myEvaluationContext, new DescriptorLabelListener() {
                    @Override
                    public void labelChanged() {
                      callback.evaluated(fullValueDescriptor.getValueText());
                    }
                  });
                }
              });
            }
            node.setPresentation(nodeIcon, presentation, myValueDescriptor.isExpandable());
          }

          private boolean setFullValueEvaluator(Renderer renderer) {
            if (renderer instanceof FullValueEvaluatorProvider) {
              XFullValueEvaluator evaluator = ((FullValueEvaluatorProvider)renderer).getFullValueEvaluator(myEvaluationContext, myValueDescriptor);
              if (evaluator != null) {
                node.setFullValueEvaluator(evaluator);
                return true;
              }
            }
            return false;
          }
        });
      }
    });
  }

  public static XValuePresentation createPresentation(ValueDescriptorImpl descriptor) {
    XValuePresentation presentation = null;
    Renderer lastLabelRenderer = descriptor.getLastLabelRenderer();
    if (lastLabelRenderer instanceof ValueLabelRenderer) {
      presentation = ((ValueLabelRenderer)lastLabelRenderer).getPresentation(descriptor);
    }
    if (presentation == null) {
      presentation = new JavaValuePresentation(descriptor);
    }
    return presentation;
  }

  public abstract static class JavaFullValueEvaluator extends XFullValueEvaluator {
    protected final EvaluationContextImpl myEvaluationContext;

    public JavaFullValueEvaluator(@NotNull String linkText, EvaluationContextImpl evaluationContext) {
      super(linkText);
      myEvaluationContext = evaluationContext;
    }

    public JavaFullValueEvaluator(EvaluationContextImpl evaluationContext) {
      myEvaluationContext = evaluationContext;
    }

    public abstract void evaluate(@NotNull XFullValueEvaluationCallback callback) throws Exception;

    protected EvaluationContextImpl getEvaluationContext() {
      return myEvaluationContext;
    }

    @Override
    public void startEvaluation(@NotNull final XFullValueEvaluationCallback callback) {
      if (callback.isObsolete()) return;
      myEvaluationContext.getManagerThread().schedule(new SuspendContextCommandImpl(myEvaluationContext.getSuspendContext()) {
        @Override
        public Priority getPriority() {
          return Priority.NORMAL;
        }

        @Override
        protected void commandCancelled() {
          callback.errorOccurred(DebuggerBundle.message("error.context.has.changed"));
        }

        @Override
        public void contextAction(@NotNull SuspendContextImpl suspendContext) throws Exception {
          if (callback.isObsolete()) return;
          evaluate(callback);
        }
      });
    }
  }

  private int myChildrenRemaining = -1;

  @Override
  public void computeChildren(@NotNull final XCompositeNode node) {
    scheduleCommand(myEvaluationContext, node, new SuspendContextCommandImpl(myEvaluationContext.getSuspendContext()) {
      @Override
      public Priority getPriority() {
        return Priority.NORMAL;
      }

      @Override
      public void contextAction(@NotNull SuspendContextImpl suspendContext) {
        myValueDescriptor.getChildrenRenderer(myEvaluationContext.getDebugProcess())
          .buildChildren(myValueDescriptor.getValue(), new ChildrenBuilder() {
          @Override
          public NodeDescriptorFactory getDescriptorManager() {
            return myNodeManager;
          }

          @Override
          public NodeManager getNodeManager() {
            return myNodeManager;
          }

          @Override
          public ValueDescriptor getParentDescriptor() {
            return myValueDescriptor;
          }

          @Override
          public void initChildrenArrayRenderer(ArrayRenderer renderer, int arrayLength) {
            renderer.START_INDEX = 0;
            if (myChildrenRemaining >= 0) {
              renderer.START_INDEX = Math.max(0, arrayLength - myChildrenRemaining);
            }
          }

          @Override
          public void addChildren(List<DebuggerTreeNode> nodes, boolean last) {
            XValueChildrenList childrenList = XValueChildrenList.EMPTY;
            if (!nodes.isEmpty()) {
              childrenList = new XValueChildrenList(nodes.size());
              for (DebuggerTreeNode treeNode : nodes) {
                NodeDescriptor descriptor = treeNode.getDescriptor();
                if (descriptor instanceof ValueDescriptorImpl) {
                  // Value is calculated already in NodeManagerImpl
                  childrenList.add(create(JavaValue.this, (ValueDescriptorImpl)descriptor, myEvaluationContext, myNodeManager, false));
                }
                else if (descriptor instanceof MessageDescriptor) {
                  childrenList.add(
                    new JavaStackFrame.DummyMessageValueNode(descriptor.getLabel(), DebuggerTreeRenderer.getDescriptorIcon(descriptor)));
                }
              }
            }
            node.addChildren(childrenList, last);
          }

          @Override
          public void setChildren(List<DebuggerTreeNode> nodes) {
            addChildren(nodes, true);
          }

          @Override
          public void setMessage(@NotNull String message,
                                 @Nullable Icon icon,
                                 @NotNull SimpleTextAttributes attributes,
                                 @Nullable XDebuggerTreeNodeHyperlink link) {
            node.setMessage(message, icon, attributes, link);
          }

          @Override
          public void addChildren(@NotNull XValueChildrenList children, boolean last) {
            node.addChildren(children, last);
          }

          @Override
          public void tooManyChildren(int remaining) {
            myChildrenRemaining = remaining;
            node.tooManyChildren(remaining);
          }

          @Override
          public void setAlreadySorted(boolean alreadySorted) {
            node.setAlreadySorted(alreadySorted);
          }

          @Override
          public void setErrorMessage(@NotNull String errorMessage) {
            node.setErrorMessage(errorMessage);
          }

          @Override
          public void setErrorMessage(@NotNull String errorMessage, @Nullable XDebuggerTreeNodeHyperlink link) {
            node.setErrorMessage(errorMessage, link);
          }

          @Override
          public boolean isObsolete() {
            return node.isObsolete();
          }
        }, myEvaluationContext);
      }
    });
  }

  protected static boolean scheduleCommand(EvaluationContextImpl evaluationContext,
                                        @NotNull final XCompositeNode node,
                                        final SuspendContextCommandImpl command) {
    if (node.isObsolete()) {
      return false;
    }
    evaluationContext.getManagerThread().schedule(new SuspendContextCommandImpl(command.getSuspendContext()) {
      @Override
      public void contextAction(@NotNull SuspendContextImpl suspendContext) throws Exception {
        if (node.isObsolete()) {
          return;
        }
        command.contextAction(suspendContext);
      }

      @Override
      protected void commandCancelled() {
        node.setErrorMessage(DebuggerBundle.message("error.context.has.changed"));
      }
    });
    return true;
  }

  @Override
  public void computeSourcePosition(@NotNull final XNavigatable navigatable) {
    computeSourcePosition(navigatable, false);
  }

  private void computeSourcePosition(@NotNull final XNavigatable navigatable, final boolean inline) {
    myEvaluationContext.getManagerThread().schedule(new SuspendContextCommandImpl(myEvaluationContext.getSuspendContext()) {
      @Override
      public Priority getPriority() {
        return inline ? Priority.LOWEST : Priority.NORMAL;
      }

      @Override
      protected void commandCancelled() {
        navigatable.setSourcePosition(null);
      }

      @Override
      public void contextAction(@NotNull SuspendContextImpl suspendContext) {
        ApplicationManager.getApplication().runReadAction(() -> {
          SourcePosition position = SourcePositionProvider.getSourcePosition(myValueDescriptor, getProject(), getDebuggerContext(), false);
          if (position != null) {
            navigatable.setSourcePosition(DebuggerUtilsEx.toXSourcePosition(position));
          }
          if (inline) {
            position = SourcePositionProvider.getSourcePosition(myValueDescriptor, getProject(), getDebuggerContext(), true);
            if (position != null) {
              navigatable.setSourcePosition(DebuggerUtilsEx.toXSourcePosition(position));
            }
          }
        });
      }
    });
  }

  @NotNull
  @Override
  public ThreeState computeInlineDebuggerData(@NotNull final XInlineDebuggerDataCallback callback) {
    computeSourcePosition(callback::computed, true);
    return ThreeState.YES;
  }

  private DebuggerContextImpl getDebuggerContext() {
    return myEvaluationContext.getDebugProcess().getDebuggerContext();
  }

  public Project getProject() {
    return myValueDescriptor.getProject();
  }

  @Override
  public boolean canNavigateToTypeSource() {
    return true;
  }

  @Override
  public void computeTypeSourcePosition(@NotNull final XNavigatable navigatable) {
    if (myEvaluationContext.getSuspendContext().isResumed()) return;
    DebugProcessImpl debugProcess = myEvaluationContext.getDebugProcess();
    debugProcess.getManagerThread().schedule(new JumpToObjectAction.NavigateCommand(getDebuggerContext(), myValueDescriptor, debugProcess, null) {
      @Override
      public Priority getPriority() {
        return Priority.HIGH;
      }

      @Override
      protected void doAction(@Nullable final SourcePosition sourcePosition) {
        if (sourcePosition != null) {
          ApplicationManager.getApplication().runReadAction(() -> navigatable.setSourcePosition(DebuggerUtilsEx.toXSourcePosition(sourcePosition)));
        }
      }
    });
  }

  @Nullable
  @Override
  public XValueModifier getModifier() {
    return myValueDescriptor.canSetValue() ? myValueDescriptor.getModifier(this) : null;
  }

  private volatile XExpression evaluationExpression = null;

  @NotNull
  @Override
  public Promise<XExpression> calculateEvaluationExpression() {
    if (evaluationExpression != null) {
      return Promises.resolvedPromise(evaluationExpression);
    }
    else {
      final AsyncPromise<XExpression> res = new AsyncPromise<>();
      myEvaluationContext.getManagerThread().schedule(new SuspendContextCommandImpl(myEvaluationContext.getSuspendContext()) {
        @Override
        public Priority getPriority() {
          return Priority.HIGH;
        }

        @Override
        public void contextAction(@NotNull SuspendContextImpl suspendContext) {
          evaluationExpression = ReadAction.compute(() -> {
            try {
              PsiElement psiExpression = getDescriptor().getTreeEvaluation(JavaValue.this, getDebuggerContext());
              if (psiExpression != null) {
                XExpression res = TextWithImportsImpl.toXExpression(new TextWithImportsImpl(psiExpression));
                // add runtime imports if any
                Set<String> imports = psiExpression.getUserData(DebuggerTreeNodeExpression.ADDITIONAL_IMPORTS_KEY);
                if (imports != null && res != null) {
                  if (res.getCustomInfo() != null) {
                    imports.add(res.getCustomInfo());
                  }
                  res = new XExpressionImpl(res.getExpression(), res.getLanguage(), StringUtil.join(imports, ","), res.getMode());
                }
                return res;
              }
            }
            catch (EvaluateException e) {
              LOG.info(e);
            }
            return null;
          });
          res.setResult(evaluationExpression);
        }
      });
      return res;
    }
  }

  @Override
  @NotNull
  public String getValueText() {
    return myValueDescriptor.getValueText();
  }

  @Nullable
  @Override
  public XReferrersProvider getReferrersProvider() {
    return new XReferrersProvider() {
      @Override
      public XValue getReferringObjectsValue() {
        ReferringObjectsProvider provider = ReferringObjectsProvider.BASIC_JDI;

        if (MemoryAgent.capabilities(getEvaluationContext().getDebugProcess()).canGetReferringObjects()) {
          provider = new MemoryAgentReferringObjectsProvider(MemoryAgent.DEFAULT_GC_ROOTS_OBJECTS_LIMIT);
        }
        return new JavaReferringObjectsValue(JavaValue.this, provider, null);
      }
    };
  }

  @Nullable
  @Override
  public XInstanceEvaluator getInstanceEvaluator() {
    return new XInstanceEvaluator() {
      @Override
      public void evaluate(@NotNull final XDebuggerEvaluator.XEvaluationCallback callback, @NotNull final XStackFrame frame) {
        myEvaluationContext.getManagerThread().schedule(new DebuggerCommandImpl() {
          @Override
          protected void commandCancelled() {
            callback.errorOccurred(DebuggerBundle.message("error.context.has.changed"));
          }

          @Override
          protected void action() {
            ValueDescriptorImpl inspectDescriptor = myValueDescriptor;
            if (myValueDescriptor instanceof WatchItemDescriptor) {
              Modifier modifier = ((WatchItemDescriptor)myValueDescriptor).getModifier();
              if (modifier != null) {
                NodeDescriptor item = modifier.getInspectItem(getProject());
                if (item != null) {
                  inspectDescriptor = (ValueDescriptorImpl)item;
                }
              }
            }
            EvaluationContextImpl evaluationContext = ((JavaStackFrame)frame).getFrameDebuggerContext(null).createEvaluationContext();
            if (evaluationContext != null) {
              callback.evaluated(create(inspectDescriptor, evaluationContext, myNodeManager));
            }
            else {
              callback.errorOccurred("Context is not available");
            }
          }
        });
      }
    };
  }

  public void setRenderer(NodeRenderer nodeRenderer, final XValueNodeImpl node) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    myValueDescriptor.setRenderer(nodeRenderer);
    reBuild(node);
  }

  public void reBuild(final XValueNodeImpl node) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    myChildrenRemaining = -1;
    node.invokeNodeUpdate(() -> {
      node.clearChildren();
      computePresentation(node, XValuePlace.TREE);
    });
  }

  @Nullable
  @Override
  public String computeInlinePresentation() {
    ValueDescriptorImpl descriptor = getDescriptor();
    return descriptor.isNull() || descriptor.isPrimitive() ? descriptor.getValueText() : null;
  }
}
