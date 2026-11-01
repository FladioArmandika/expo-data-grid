import ExpoModulesCore

public class ExpoDataGridModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoDataGrid")

    Function("hello") {
      return "Hello world! 👋"
    }

    AsyncFunction("setValueAsync") { (value: String) in
    }

    View(ExpoDataGridView.self) {
      Events("onTap")
    }

    Class(ExpoDataGridModuleSharedObject.self) {
      Constructor { () -> ExpoDataGridModuleSharedObject in
        return ExpoDataGridModuleSharedObject()
      }

      Property("count") { (ref: ExpoDataGridModuleSharedObject) -> Int in
        return ref.count
      }
      .set { (ref: ExpoDataGridModuleSharedObject, count: Int) in
        ref.count = count
      }
    }
  }
}
