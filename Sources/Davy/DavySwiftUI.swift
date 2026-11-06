import SwiftUI

public extension EnvironmentValues {
    /// The app's Davy instance. `nil` by default, so previews never log or send events.
    @Entry var analytics: Davy?
}

public extension View {
    /// Logs a breadcrumb named `name` when this view appears.
    ///
    /// Does nothing if no ``Davy`` instance is set in the environment with `\.analytics`.
    func logBreadcrumb(_ name: String) -> some View {
        modifier(BreadcrumbModifier(name: name))
    }

    /// Presents ``DavyDebugView`` in a sheet when `isPresented` is `true`, listing the
    /// pending and sent events of the ``Davy`` instance set in the environment with
    /// `\.analytics`.
    func davyDebugSheet(isPresented: Binding<Bool>) -> some View {
        sheet(isPresented: isPresented) {
            DavyDebugView()
        }
    }
}

private struct BreadcrumbModifier: ViewModifier {
    @Environment(\.analytics) private var analytics
    let name: String

    func body(content: Content) -> some View {
        content.onAppear {
            analytics?.log(.breadcrumb(name))
        }
    }
}
