/* vim:set ts=2 sw=2 sts=2 et: */
/*
 * Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
var gPane = null;
var gTab = null;
var gDebuggee = null;
var gDebugger = null;

function test() {
  debug_tab_pane(STACK_URL, function(aTab, aDebuggee, aPane) {
    gTab = aTab;
    gDebuggee = aDebuggee;
    gPane = aPane;
    gDebugger = gPane.contentWindow;

    testNonEnumProperties();
  });
}

function testNonEnumProperties() {
  gDebugger.DebuggerController.activeThread.addOneTimeListener("framesadded", function() {
    Services.tm.currentThread.dispatch({ run: function() {
      let testScope = gDebugger.DebuggerView.Variables.addScope("test-scope");
      let testVar = testScope.addVar("foo");
      testVar.addProperties({
        foo: {
          value: "bar",
          enumerable: true
        },

        bar: {
          value: "foo",
          enumerable: false
        }
      });

      testScope.expand();
      testVar.expand();

      let details = testVar._enum;
      let nonenum = testVar._nonenum;

      is(details.childNodes.length, 1,
        "There should be just one property in the .details container.");

      ok(details.hasAttribute("open"),
        ".details container should be visible.");

      is(nonenum.childNodes.length, 1,
        "There should be just one property in the .nonenum container.");

      ok(nonenum.hasAttribute("open"),
        ".nonenum container should be visible.");

      // Uncheck 'show hidden properties'.
      gDebugger.DebuggerView.Options._showVariablesNonEnumItem.setAttribute("checked", "false");
      gDebugger.DebuggerView.Options._toggleShowVariablesNonEnum();

      ok(details.hasAttribute("open"),
        ".details container should stay visible.");

      ok(!nonenum.hasAttribute("open"),
        ".nonenum container should become hidden.");

      // Check 'show hidden properties'.
      gDebugger.DebuggerView.Options._showVariablesNonEnumItem.setAttribute("checked", "true");
      gDebugger.DebuggerView.Options._toggleShowVariablesNonEnum();

      ok(details.hasAttribute("open"),
        ".details container should stay visible.");

      ok(nonenum.hasAttribute("open"),
        ".nonenum container should become visible.");

      testVar.collapse();

      ok(!details.hasAttribute("open"),
        ".details container should be hidden.");

      ok(!nonenum.hasAttribute("open"),
        ".nonenum container should be hidden.");

      // Uncheck 'show hidden properties'.
      gDebugger.DebuggerView.Options._showVariablesNonEnumItem.setAttribute("checked", "false");
      gDebugger.DebuggerView.Options._toggleShowVariablesNonEnum();

      ok(!details.hasAttribute("open"),
        ".details container should stay hidden.");

      ok(!nonenum.hasAttribute("open"),
        ".nonenum container should stay hidden.");

      // Check 'show hidden properties'.
      gDebugger.DebuggerView.Options._showVariablesNonEnumItem.setAttribute("checked", "true");
      gDebugger.DebuggerView.Options._toggleShowVariablesNonEnum();

      gDebugger.DebuggerController.activeThread.resume(function() {
        closeDebuggerAndFinish();
      });
    }}, 0);
  });

  gDebuggee.simpleCall();
}

registerCleanupFunction(function() {
  removeTab(gTab);
  gPane = null;
  gTab = null;
  gDebuggee = null;
  gDebugger = null;
});
