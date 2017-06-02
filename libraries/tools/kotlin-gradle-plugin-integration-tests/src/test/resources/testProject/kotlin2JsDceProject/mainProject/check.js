(function() {
    var status = exampleapp.exampleapp.status;
    //java.lang.System.out.println(Object.keys(exampleapp.exampleapp).toString());
    if (status !== "foo") {
        throw new Error("Unexpected status: " + status);
    }
})();
