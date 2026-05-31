import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'pages/audio_page.dart';
import 'pages/connection_page.dart';

void main() {
  runApp(const ProviderScope(child: MaimaiHomeMobileApp()));
}

class MaimaiHomeMobileApp extends StatelessWidget {
  const MaimaiHomeMobileApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'maimai Home Mobile',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.pinkAccent),
      ),
      home: const ConnectionPage(),
      routes: {
        '/audio': (_) => const AudioPage(),
        '/connection': (_) => const ConnectionPage(),
      },
    );
  }
}
