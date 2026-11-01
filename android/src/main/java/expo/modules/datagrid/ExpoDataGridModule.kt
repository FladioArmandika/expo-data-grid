package expo.modules.datagrid

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoDataGridModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoDataGrid")

    Function("hello") {
      "Hello world! 👋"
    }

    AsyncFunction("setValueAsync") { value: String ->
    }

    View(ExpoDataGridView::class) {
      // Defines an event that the view can send to JavaScript.
      Events("onTap")
    }

    Class(ExpoDataGridModuleSharedObject::class) {
      Constructor {
        val instance = ExpoDataGridModuleSharedObject(appContext)
        return@Constructor instance
      }

      Property("count")
        .get { ref: ExpoDataGridModuleSharedObject ->
          ref.count
        }
        .set { ref: ExpoDataGridModuleSharedObject, count: Int ->
          ref.count = count
        }
    }
  }
}
