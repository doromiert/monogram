# Import alongside the host's existing services.prosody configuration.
{ ... }: {
  services.prosody.extraPluginPaths = [ ./. ];
  services.prosody.extraModules = [ "monogram_schedule" ];
}
